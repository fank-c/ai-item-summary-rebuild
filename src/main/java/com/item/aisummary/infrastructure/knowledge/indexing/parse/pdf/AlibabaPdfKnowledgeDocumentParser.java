package com.item.aisummary.infrastructure.knowledge.indexing.parse.pdf;

import com.item.aisummary.application.knowledge.indexing.parse.ContentBlock;
import com.item.aisummary.application.knowledge.indexing.parse.ContentBlockType;
import com.item.aisummary.application.knowledge.indexing.parse.KnowledgeDocumentParser;
import com.item.aisummary.application.knowledge.indexing.parse.ParsedDocument;
import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.application.knowledge.indexing.source.PreparedSource;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.pdf.DocMindPdfParser.Layout;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.pdf.DocMindPdfParser.Result;
import com.item.aisummary.domain.knowledge.value.SourceLocation;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

/**
 * 基于阿里云文档智能（Document Mind）与本地 Apache PDFBox 的 PDF 生产文档解析策略实现。
 *
 * <p>遵循如下解析规则与架构防御边界：
 * <ul>
 *   <li>本地 PDFBox 容器防御：PDFBox 仅用于内存流中检验 PDF 容器结构合法性、拦截损坏/加密文件，并限制总物理页数不超过 {@value #MAX_PAGES}（2,000 页）；本地不执行文本提取。</li>
 *   <li>云端智能文档分析：正文提取、版面分析与扫描件 OCR 统一委托显式配置的阿里云文档智能大模型服务（{@link DocMindPdfParser}）。</li>
 *   <li>页面覆盖与防漏页核验：严格校验云端识别完成的页面集合必须完整覆盖本地侦测到的实际总页数（兼容严格的 0 基或 1 基页清单）；缺页直接拒绝，防止扫描件因网络或识别波动导致静默丢页。</li>
 *   <li>阅读顺序与版面排序：严格按照 DocMind 版面对象的 {@code layout.index} 升序排序（要求非负、唯一且严格递增），确保多栏、图文混排文档的正确正文阅读流。</li>
 *   <li>标题策略与层级降级：DocMind 的 {@code layout.level} 仅表示版面排版嵌套深度，不可作为可信的文档大纲层级；因此标题版面类型标记为 {@link ContentBlockType#HEADING}，但全局标题路径 {@code headingPath} 设为 {@code null}，标题本身及跨页切换均触发硬边界（{@code boundaryBefore = true}）。</li>
 *   <li>表格与图文提取：表格块优先保留视觉大模型产出的 Markdown/HTML（保留跨行跨列合并属性），图示块在包含说明（{@code llmResult}）时保留纯文本；空白文档或不可表示的空版面明确拒绝。</li>
 *   <li>位置与偏移计算：以 {@code \n\n} 拼接构建逻辑全文，计算 UTF-16 半开区间 {@code [start_offset, end_offset)}；文档智能官方 0 基页码映射为系统 1 基物理页码（{@code pageNum + 1}）。</li>
 *   <li>安全防御预算（硬编码常数）：最大物理页数限制 {@value #MAX_PAGES}（2,000 页），最大版面数量限制 {@value #MAX_LAYOUTS}（100,000），最大提取字符限制 {@value #MAX_EXTRACTED_CHARS}（50,000,000 字符）。</li>
 * </ul>
 */
public final class AlibabaPdfKnowledgeDocumentParser implements KnowledgeDocumentParser {
    /** PDF 文档最大物理页数安全防御上限。 */
    private static final int MAX_PAGES = 2000;
    /** PDF 提取版面最大数量安全防御上限。 */
    private static final int MAX_LAYOUTS = 100_000;
    /** PDF 提取总字符数安全防御上限。 */
    private static final int MAX_EXTRACTED_CHARS = 50_000_000;
    private final long maxSourceSize;
    private final DocMindPdfParser docMind;

    /**
     * 构造阿里云文档智能 PDF 解析策略。
     *
     * @param maxSourceSize 单个源文件允许的最大字节预算（必须为正且小于 Integer.MAX_VALUE）
     * @param docMind 云端文档智能解析网关客户端
     * @throws IllegalArgumentException 当源文件预算或 DocMind 客户端无效时抛出
     */
    public AlibabaPdfKnowledgeDocumentParser(long maxSourceSize, DocMindPdfParser docMind) {
        if (maxSourceSize < 1 || maxSourceSize >= Integer.MAX_VALUE || docMind == null) {
            throw new IllegalArgumentException("PDF 解析预算或 DocMind 客户端无效");
        }
        this.maxSourceSize = maxSourceSize;
        this.docMind = docMind;
    }

    @Override public FileFormat format() { return FileFormat.PDF; }

    /**
     * 解析已准备好的 PDF 源文件。
     *
     * <p>首先通过本地 PDFBox 预检 PDF 格式、密码与物理页数；预检通过后将二进制原字节提交至云端进行 OCR 与版面分析，
     * 核对页面完整覆盖后组装输出结构化文档。
     *
     * @param source 包含原始受控字节的源文件包装对象
     * @return 解析完成的统一结构化文档（ParsedDocument）
     * @throws IllegalArgumentException 当源文件为空白、加密、损坏、超页数限制或云端解析失败/缺页时抛出
     */
    @Override
    public ParsedDocument parse(PreparedSource source) {
        if (source == null || source.format() != FileFormat.PDF
                || source.size() == 0 || source.size() > maxSourceSize) {
            throw new IllegalArgumentException("PDF 源文件无效或超过解析限制");
        }
        byte[] pdfBytes;
        try (InputStream input = source.openStream()) {
            pdfBytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalArgumentException("PDF 源文件读取失败", exception);
        }
        int pageCount;
        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            if (pdf.isEncrypted()) throw new IllegalArgumentException("不支持加密 PDF");
            pageCount = pdf.getNumberOfPages();
            if (pageCount < 1 || pageCount > MAX_PAGES) {
                throw new IllegalArgumentException("PDF 页数超过解析限制");
            }
        } catch (InvalidPasswordException exception) {
            throw new IllegalArgumentException("不支持加密 PDF");
        } catch (IOException exception) {
            throw new IllegalArgumentException("PDF 格式无效或已损坏");
        }
        Result result = docMind.parse(pdfBytes);
        validateProcessedPages(result, pageCount);
        return toParsedDocument(result.layouts(), pageCount);
    }

    /**
     * 将云端返回的版面列表按阅读顺序排序并转换为统一的结构化文档。
     *
     * @param input 云端返回的原始版面列表
     * @param pageCount 本地 PDFBox 校验确认的实际总物理页数
     * @return 转换后的统一结构化文档（ParsedDocument）
     * @throws IllegalArgumentException 当版面列表为空、超预算、存在空版面、阅读顺序无序或提取字符超限时抛出
     */
    static ParsedDocument toParsedDocument(List<Layout> input, int pageCount) {
        if (input == null || input.isEmpty() || pageCount < 1 || pageCount > MAX_PAGES || input.size() > MAX_LAYOUTS) {
            throw new IllegalArgumentException("PDF 解析结果无效或超过解析限制");
        }
        var layouts = input.stream().filter(layout -> layout != null)
                .sorted(Comparator.comparing(layout -> layout.index() == null ? Integer.MAX_VALUE : layout.index()))
                .toList();
        if (layouts.size() != input.size()) throw new IllegalArgumentException("PDF 解析结果包含空版面");
        int previousIndex = -1;
        for (var layout : layouts) {
            if (layout.index() == null || layout.index() < 0 || layout.index() <= previousIndex) {
                throw new IllegalArgumentException("PDF 解析结果阅读顺序无效");
            }
            previousIndex = layout.index();
        }

        var blocks = new ArrayList<ContentBlock>();
        int logicalOffset = 0;
        Integer previousPage = null;
        for (var layout : layouts) {
            if (layout.pageNum() == null || layout.pageNum() < 0 || layout.pageNum() >= pageCount) {
                throw new IllegalArgumentException("PDF 解析结果页码无效");
            }
            int page = layout.pageNum() + 1; // Document Mind layout.pageNum 是 0 基页码，转换为系统 1 基物理页码
            String type = normalize(layout.type());
            String subtype = normalize(layout.subType());
            String content = content(layout, type, subtype);
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("PDF 解析结果包含无法表示的空版面");
            }
            int separatorLength = blocks.isEmpty() ? 0 : 2;
            if (content.length() > MAX_EXTRACTED_CHARS - logicalOffset - separatorLength) {
                throw new IllegalArgumentException("PDF 提取文本超过解析限制");
            }

            ContentBlockType blockType = isHeading(type, subtype) ? ContentBlockType.HEADING
                    : "table".equals(type) ? ContentBlockType.TABLE : ContentBlockType.TEXT;
            // 标题或者跨页时标记硬边界，重置切片重叠窗口
            boolean boundary = blockType == ContentBlockType.HEADING || (previousPage != null && previousPage != page);
            // layout.level 仅表示排版嵌套，不能作为可靠的大纲树，因此 headingPath 统一置空
            int start = logicalOffset + separatorLength;
            logicalOffset = start + content.length();
            blocks.add(new ContentBlock(blocks.size(), blockType, content, null,
                    new SourceLocation(start, logicalOffset, page, page), boundary));
            previousPage = page;
        }
        return new ParsedDocument(blocks);
    }

    /**
     * 核验云端已识别完成的页面清单是否完整覆盖本地 PDF 物理总页数。
     *
     * <p>DocMind 不同版本/接口在 outputFormatResult 中可能返回 0 基（0 ~ N-1）或 1 基（1 ~ N）页号集合，
     * 本方法同时兼容两种严格合法的全覆盖模式，任意一种完全覆盖即判定通过。
     *
     * @param result 云端解析结果（包含 processedPageIds）
     * @param pageCount 本地 PDFBox 确定的物理总页数
     * @throws IllegalArgumentException 当结果为空或页面清单未完整覆盖时抛出
     */
    private static void validateProcessedPages(Result result, int pageCount) {
        if (result == null || result.layouts() == null || result.processedPageIds().size() != pageCount) {
            throw new IllegalArgumentException("PDF 解析结果未覆盖全部页面");
        }
        boolean zeroBased = true;
        boolean oneBased = true;
        for (int page = 0; page < pageCount; page++) {
            zeroBased &= result.processedPageIds().contains(page);
            oneBased &= result.processedPageIds().contains(page + 1);
        }
        if (!zeroBased && !oneBased) throw new IllegalArgumentException("PDF 解析结果未覆盖全部页面");
    }

    /**
     * 判断版面类型是否属于文档标题。
     *
     * @param type 版面一级类型（已小写规范化）
     * @param subtype 版面二级子类型（已小写规范化）
     * @return 若为主标题、副标题、段落标题等则返回 true
     */
    private static boolean isHeading(String type, String subtype) {
        return "title".equals(type) || "doc_title".equals(subtype) || "doc_subtitle".equals(subtype)
                || "para_title".equals(subtype) || "contents_title".equals(subtype) || "cate_title".equals(subtype);
    }

    /**
     * 按照内容类型提取版面对象的有效文本或富文本表示。
     *
     * <ul>
     *   <li>表格：优先使用视觉大模型生成的 HTML/Markdown（保留单元格跨行合并信息）。</li>
     *   <li>图示：当视觉大模型生成了图片说明（llmResult）时保留描述文本。</li>
     *   <li>普通段落：优先使用纯文本 text，缺省回退到大模型结果。</li>
     * </ul>
     *
     * @param layout 版面对象
     * @param type 规范化的一级类型
     * @param subtype 规范化的二级类型
     * @return 提取的正文内容；无法提取有效内容时返回 null
     */
    private static String content(Layout layout, String type, String subtype) {
        if ("table".equals(type)) {
            // 保留云端返回的 Markdown/HTML 原文（含跨行跨列合并属性），不在本地重新渲染
            if (layout.llmResult() != null && !layout.llmResult().isBlank()) return layout.llmResult();
            if (layout.markdownContent() != null && !layout.markdownContent().isBlank()) return layout.markdownContent();
        }
        if (("figure".equals(type) || "picture".equals(subtype))
                && layout.llmResult() != null && !layout.llmResult().isBlank()) {
            return layout.llmResult().strip();
        }
        if (layout.text() != null && !layout.text().isBlank()) return layout.text();
        if (layout.llmResult() != null && !layout.llmResult().isBlank()) return layout.llmResult().strip();
        return null;
    }

    /**
     * 将可能为 null 的字符串转为小写规范化表示，便于类型匹配。
     */
    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
    }
}
