package com.item.aisummary.infrastructure.knowledge.indexing.parse.docx;

import com.item.aisummary.application.knowledge.indexing.parse.ContentBlock;
import com.item.aisummary.application.knowledge.indexing.parse.ContentBlockType;
import com.item.aisummary.application.knowledge.indexing.parse.KnowledgeDocumentParser;
import com.item.aisummary.application.knowledge.indexing.parse.ParsedDocument;
import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.application.knowledge.indexing.source.PreparedSource;
import com.item.aisummary.domain.knowledge.value.Heading;
import com.item.aisummary.domain.knowledge.value.SourceLocation;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.IRunElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFSDT;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;

/**
 * DOCX（Office Word）文档解析策略实现。
 *
 * <p>解析规则与防御边界：
 * <ul>
 *   <li>仅支持 DOCX 格式，要求输入非空且在大小预算范围内。</li>
 *   <li>正文仅支持顶层段落（paragraph）与表格（table），保持原有交错阅读顺序；其他正文内容容器（如 SDT、altChunk）明确拒绝。</li>
 *   <li>普通图片跳过，保留同段及单元格文字，不做 OCR、不生成图片描述或占位文本；纯图片文档因没有有效文字而失败。</li>
 *   <li>数学公式、文本框、嵌入对象与嵌套表格仍明确拒绝；外部超链接仅提取展示文本，不访问网络；正文外页眉页脚不提取。</li>
 *   <li>标题层级由 {@link DocxHeadingResolver} 严格判定；基于逻辑全文拼接精确计算内容块 UTF-16 偏移半开区间。</li>
 *   <li>在 POI 实例化前由 {@link DocxPreflightChecker} 执行 ZIP 预算与 StAX 结构安全预检；对外异常统一固定脱敏，不泄漏原始异常或文档细节。</li>
 * </ul>
 */
public final class DocxKnowledgeDocumentParser implements KnowledgeDocumentParser {
    /** 单个 DOCX 允许提取的最大内容块数量防御上限。 */
    static final int MAX_BLOCKS = 100_000;
    /** 单个 DOCX 允许提取的最大文本总字符数防御上限。 */
    static final long MAX_TOTAL_CHARS = 50_000_000L;

    private final long maxSourceSize;

    /**
     * 构造 DOCX 文档解析策略。
     *
     * @param maxSourceSize 单个源文件允许的最大字节预算（必须为正且小于 Integer.MAX_VALUE）
     * @throws IllegalArgumentException 当大小预算非法时抛出
     */
    public DocxKnowledgeDocumentParser(long maxSourceSize) {
        if (maxSourceSize < 1 || maxSourceSize >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("源文件大小上限必须为正且小于 Integer.MAX_VALUE 字节");
        }
        this.maxSourceSize = maxSourceSize;
    }

    @Override
    public FileFormat format() {
        return FileFormat.DOCX;
    }

    /**
     * 解析 DOCX 源文件为结构化文档。
     *
     * <p>流程：
     * <ol>
     *   <li>大小与格式基础校验；</li>
     *   <li>调用 {@link DocxPreflightChecker#preflight(PreparedSource)} 执行流解压比与 XML 结构安全前置预检；</li>
     *   <li>通过 Apache POI 解析正文顶层段落与表格，保持交错阅读顺序；</li>
     *   <li>基于段落间固定 {@code \n\n} 拼接假设计算全文 UTF-16 偏移半开区间 {@code [start_offset, end_offset)}。</li>
     * </ol>
     *
     * @param source 包含原始受控字节的源文件包装对象
     * @return 转换后的统一结构化文档（ParsedDocument）
     * @throws IllegalArgumentException 当文件损坏、超预算、缺少有效内容、包含嵌套表格或不支持容器时抛出
     */
    @Override
    public ParsedDocument parse(PreparedSource source) {
        if (source == null) {
            throw new IllegalArgumentException("源文件对象不能为空");
        }
        if (source.format() != FileFormat.DOCX) {
            throw new IllegalArgumentException("源文件格式不匹配");
        }
        if (source.size() <= 0) {
            throw new IllegalArgumentException("文档内容不能为空白");
        }
        if (source.size() > maxSourceSize) {
            throw new IllegalArgumentException("源文件大小超过预算限制");
        }

        DocxPreflightChecker.preflight(source);

        List<RawBlock> rawBlocks = parseDocumentWithPoi(source);

        if (rawBlocks.isEmpty()) {
            throw new IllegalArgumentException("文档没有可解析的有效内容");
        }
        if (rawBlocks.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("文档内容块数量超过解析限制");
        }

        int currentOffset = 0;
        List<ContentBlock> blocks = new ArrayList<>(rawBlocks.size());
        for (int i = 0; i < rawBlocks.size(); i++) {
            RawBlock raw = rawBlocks.get(i);
            int startOffset = currentOffset;
            int endOffset = startOffset + raw.text().length();
            SourceLocation location = new SourceLocation(startOffset, endOffset, null, null);
            blocks.add(new ContentBlock(
                    i,
                    raw.type(),
                    raw.text(),
                    raw.headingPath(),
                    location,
                    raw.boundaryBefore()
            ));
            currentOffset = endOffset + 2; // "\n\n"
        }

        return new ParsedDocument(blocks);
    }

    /**
     * 使用 Apache POI 打开并解析 DOCX 文档，捕获异常并映射为固定安全错误信息。
     */
    private List<RawBlock> parseDocumentWithPoi(PreparedSource source) {
        try (InputStream in = source.openStream();
             XWPFDocument document = new XWPFDocument(in)) {
            return extractRawBlocks(document);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("DOCX 格式无效或已损坏");
        }
    }

    /**
     * 提取 DOCX 主体内的全部顶层块元素，保持段落与表格的交错阅读顺序。
     *
     * <p>规则：
     * <ul>
     *   <li>拒绝 altChunk 与 sdt 等非普通正文容器；</li>
     *   <li>段落通过 {@link DocxHeadingResolver} 识别标题层级，维护全局有序的标题路径 {@code activeHeadings} 快照；</li>
     *   <li>标题块带有硬边界标记（boundaryBefore = true）；</li>
     *   <li>表格提取为制表符与换行符分隔的纯文本表格块；</li>
     *   <li>累加字符数并在超过 {@value #MAX_TOTAL_CHARS} 时防御拒绝。</li>
     * </ul>
     */
    private List<RawBlock> extractRawBlocks(XWPFDocument document) {
        if (document.getDocument() != null && document.getDocument().getBody() != null) {
            if (document.getDocument().getBody().sizeOfAltChunkArray() > 0) {
                throw new IllegalArgumentException("不支持的内容容器");
            }
            if (document.getDocument().getBody().sizeOfSdtArray() > 0) {
                throw new IllegalArgumentException("不支持的内容容器");
            }
        }

        List<RawBlock> rawBlocks = new ArrayList<>();
        List<Heading> activeHeadings = new ArrayList<>();
        XWPFStyles styles = document.getStyles();
        long totalChars = 0;

        for (IBodyElement element : document.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                validateParagraph(paragraph);
                String text = extractParagraphText(paragraph);
                if (text.isBlank()) {
                    continue;
                }
                totalChars += text.length();
                if (totalChars > MAX_TOTAL_CHARS) {
                    throw new IllegalArgumentException("DOCX 总字符数超过解析限制");
                }
                Integer headingLevel = DocxHeadingResolver.resolveHeadingLevel(paragraph, styles);
                if (headingLevel != null) {
                    activeHeadings.removeIf(h -> h.level() >= headingLevel);
                    String title = text.strip();
                    if (!title.isBlank()) {
                        activeHeadings.add(new Heading(headingLevel, title));
                    }
                    rawBlocks.add(new RawBlock(
                            ContentBlockType.HEADING,
                            text,
                            List.copyOf(activeHeadings),
                            true
                    ));
                } else {
                    rawBlocks.add(new RawBlock(
                            ContentBlockType.TEXT,
                            text,
                            List.copyOf(activeHeadings),
                            false
                    ));
                }
            } else if (element instanceof XWPFTable table) {
                String tableText = extractTableText(table);
                if (tableText.isBlank()) {
                    continue;
                }
                totalChars += tableText.length();
                if (totalChars > MAX_TOTAL_CHARS) {
                    throw new IllegalArgumentException("DOCX 总字符数超过解析限制");
                }
                rawBlocks.add(new RawBlock(
                        ContentBlockType.TABLE,
                        tableText,
                        List.copyOf(activeHeadings),
                        false
                ));
            } else {
                throw new IllegalArgumentException("不支持的内容容器");
            }
            if (rawBlocks.size() > MAX_BLOCKS) {
                throw new IllegalArgumentException("文档内容块数量超过解析限制");
            }
        }
        return rawBlocks;
    }

    /**
     * 校验不支持的数学公式和嵌入对象；普通图片允许跳过，文本框由 XML 前置预检拒绝。
     *
     * @param paragraph POI XWPFParagraph 段落对象
     * @throws IllegalArgumentException 当段落包含公式或嵌入对象时抛出
     */
    static void validateParagraph(XWPFParagraph paragraph) {
        var ctp = paragraph.getCTP();
        if (ctp != null && (ctp.sizeOfOMathArray() > 0 || ctp.sizeOfOMathParaArray() > 0)) {
            throw new IllegalArgumentException("不支持数学公式");
        }
        for (IRunElement runElement : paragraph.getIRuns()) {
            if (runElement instanceof XWPFRun run) {
                CTR ctr = run.getCTR();
                if (ctr != null && ctr.sizeOfObjectArray() > 0) {
                    throw new IllegalArgumentException("不支持嵌入对象");
                }
            }
        }
    }

    /**
     * 合并段落中的全部 Run 提取纯文本正文。
     *
     * <p>过滤修订删除内容（delText），提取行内 SDT 文本，并将 Windows/Mac 换行统统规范化为 LF（\n）。
     * 普通图片由 Run 文本提取自然跳过，但同一 Run 的普通文字仍保留；不使用包含图片说明的 Run.toString()。
     *
     * @param paragraph 段落对象
     * @return 规整后的段落文本
     */
    static String extractParagraphText(XWPFParagraph paragraph) {
        StringBuilder sb = new StringBuilder();
        for (IRunElement runElement : paragraph.getIRuns()) {
            if (runElement instanceof XWPFRun run) {
                CTR ctr = run.getCTR();
                if (ctr != null && ctr.getDelTextArray() != null && ctr.getDelTextArray().length > 0) {
                    continue;
                }
                String runText = run.text();
                if (runText != null) {
                    sb.append(runText);
                }
            } else if (runElement instanceof XWPFSDT sdt) {
                String sdtText = sdt.getContent() != null ? sdt.getContent().getText() : null;
                if (sdtText != null) {
                    sb.append(sdtText);
                }
            }
        }
        return normalizeLineEndings(sb.toString());
    }

    /**
     * 将 Word 表格转换为纯文本表示。
     *
     * <p>同一行的单元格之间以 Tab（\t）分隔，行之间以换行（\n）分隔，空单元格保留为空字符串。
     *
     * @param table POI XWPFTable 表格对象
     * @return 表格纯文本表示
     */
    static String extractTableText(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        List<String> rowTexts = new ArrayList<>(rows.size());
        for (XWPFTableRow row : rows) {
            List<XWPFTableCell> cells = row.getTableCells();
            if (cells == null || cells.isEmpty()) {
                rowTexts.add("");
                continue;
            }
            List<String> cellTexts = new ArrayList<>(cells.size());
            for (XWPFTableCell cell : cells) {
                cellTexts.add(extractCellText(cell));
            }
            rowTexts.add(String.join("\t", cellTexts));
        }
        return String.join("\n", rowTexts);
    }

    /**
     * 提取单元格文本，若单元格包含多个段落则以换行（\n）拼接。
     *
     * @param cell POI XWPFTableCell 单元格对象
     * @return 单元格文本
     * @throws IllegalArgumentException 当单元格内嵌子表格或复杂容器时抛出
     */
    static String extractCellText(XWPFTableCell cell) {
        if (!cell.getTables().isEmpty()) {
            throw new IllegalArgumentException("不支持嵌套表格");
        }
        for (IBodyElement be : cell.getBodyElements()) {
            if (be instanceof XWPFTable) {
                throw new IllegalArgumentException("不支持嵌套表格");
            }
            if (be instanceof XWPFSDT) {
                throw new IllegalArgumentException("不支持的内容容器");
            }
        }
        List<XWPFParagraph> paragraphs = cell.getParagraphs();
        if (paragraphs == null || paragraphs.isEmpty()) {
            return "";
        }
        List<String> paraTexts = new ArrayList<>(paragraphs.size());
        for (XWPFParagraph p : paragraphs) {
            validateParagraph(p);
            paraTexts.add(extractParagraphText(p));
        }
        boolean allEmpty = true;
        for (String pt : paraTexts) {
            if (!pt.isEmpty()) {
                allEmpty = false;
                break;
            }
        }
        if (allEmpty) {
            return "";
        }
        return String.join("\n", paraTexts);
    }

    /**
     * 统一换行符工具：将 CRLF 和 CR 规整为 LF。
     */
    private static String normalizeLineEndings(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 内部承载解析过程中的临时块数据。
     *
     * @param type 块类型
     * @param text 规整后的文本
     * @param headingPath 当前块对应的有序标题大纲路径快照
     * @param boundaryBefore 是否构成分块硬边界
     */
    record RawBlock(ContentBlockType type, String text, List<Heading> headingPath, boolean boundaryBefore) {}
}
