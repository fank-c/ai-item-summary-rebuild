package com.item.aisummary.infrastructure.knowledge.indexing.parse;

import com.item.aisummary.application.knowledge.indexing.parse.ContentBlock;
import com.item.aisummary.application.knowledge.indexing.parse.ContentBlockType;
import com.item.aisummary.application.knowledge.indexing.parse.KnowledgeDocumentParser;
import com.item.aisummary.application.knowledge.indexing.parse.ParsedDocument;
import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.application.knowledge.indexing.source.PreparedSource;
import com.item.aisummary.domain.knowledge.value.Heading;
import com.item.aisummary.domain.knowledge.value.SourceLocation;
import java.util.ArrayList;
import java.util.List;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.parser.block.BlockStart;

/**
 * 基于 CommonMark 与 GFM 表格扩展的 Markdown 文档解析策略实现。
 *
 * <p>遵循如下解析规则与安全预算边界：
 * <ul>
 *   <li>仅识别顶层块级结构（top-level blocks），正文严格从规整后的全文 UTF-16 原文截取，不使用 Markdown renderer 重新序列化。</li>
 *   <li>列表（ListBlock）与引用块（BlockQuote）整体作为单个完整的 {@link ContentBlockType#TEXT} 块输出，不递归输出嵌套段落，防止嵌套标题污染全局标题路径。</li>
 *   <li>代码块（FencedCodeBlock 与 IndentedCodeBlock）完整保留围栏与缩进，标识为 {@link ContentBlockType#CODE}。</li>
 *   <li>GFM 表格（TableBlock）保留完整表格文本，标识为 {@link ContentBlockType#TABLE}。</li>
 *   <li>各级标题（Heading）保留 Markdown 原文前缀（如 {@code #} 或 Setext 换行线），提取标题纯文本（去除外层修饰但保留行内文本）用于维护有序的全局标题路径 {@link Heading} 快照。
 *       若标题内容为空白，降级标记为 TEXT，清空当前及更深层级的标题路径但不虚构标题项；标题每次构成硬边界。</li>
 *   <li>水平分割线（ThematicBreak）不作为单独内容块输出，但为紧随其后的下一个内容块标记硬边界（{@code boundaryBefore = true}）。</li>
 *   <li>不下载外部图片、不抓取链接、不执行 HTML 或内嵌脚本；HTML 块与参考链接定义保留为普通 TEXT 原文。</li>
 *   <li>行内内容采用轻量自定义解析器直接保留原文本，避免为行内强调、链接构建大量复杂的行内 AST 节点。</li>
 *   <li>固定防御预算（硬编码常数，非外部可调参数）：最大输出块数限制为 {@link Utf8TextDecoder#MAX_BLOCKS}（100,000），
 *       最大允许解析行数为 {@code 1,000,000}，嵌套容器深度保护阈值为 {@code 128}；超限均明确拒绝。</li>
 *   <li>来源位置计算基于 CommonMark 块级 SourceSpans，精确映射到全文行首索引截取 UTF-16 半开区间 {@code [start_offset, end_offset)}；
 *       若节点缺少 spans 或行索引越界，明确抛出异常拒绝，不进行估算。页码保持为 {@code null}。</li>
 * </ul>
 */
public final class MarkdownKnowledgeDocumentParser implements KnowledgeDocumentParser {
    /** Markdown 最大允许解析行数安全防御上限。 */
    private static final int MAX_LINES = 1_000_000;
    /** Markdown 容器嵌套深度安全防御上限。 */
    private static final int MAX_NESTING = 128;
    private final long maxSourceSize;
    private final Parser parser;

    /**
     * 构造 Markdown 解析器并初始化定制的 CommonMark Parser 管道。
     *
     * @param maxSourceSize 单个源文件允许的最大字节预算（必须为正且小于 Integer.MAX_VALUE）
     * @throws IllegalArgumentException 当大小预算非法时抛出
     */
    public MarkdownKnowledgeDocumentParser(long maxSourceSize) {
        if (maxSourceSize < 1 || maxSourceSize >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("解析源文件大小上限必须为正且小于 Integer.MAX_VALUE 字节");
        }
        this.maxSourceSize = maxSourceSize;
        this.parser = Parser.builder()
                // 只需要结构和块级位置；保留行内原语法，不构建无用的强调/链接等大量 AST 节点。
                .includeSourceSpans(IncludeSourceSpans.BLOCKS)
                .inlineParserFactory(context -> (lines, node) -> node.appendChild(new Text(lines.getContent())))
                // 在新增嵌套容器之前限制深度，而不是等巨大 AST 完成后才检查。
                .customBlockParserFactory((state, matched) -> {
                    int depth = 0;
                    for (Node node = matched.getMatchedBlockParser().getBlock(); node != null; node = node.getParent()) {
                        if (++depth > MAX_NESTING) {
                            throw new IllegalArgumentException("Markdown 嵌套层级超过解析限制");
                        }
                    }
                    return BlockStart.none();
                })
                .extensions(List.of(TablesExtension.create()))
                .build();
    }

    @Override public FileFormat format() { return FileFormat.MD; }

    /**
     * 解析已准备好的 Markdown 源文件。
     *
     * @param source 包含原始受控字节的源文件包装对象
     * @return 解析完成的统一结构化文档（ParsedDocument）
     * @throws IllegalArgumentException 当源文件为空白、仅含分割线、超出嵌套深度/行数/块数限制或 UTF-8 解码失败时抛出
     */
    @Override
    public ParsedDocument parse(PreparedSource source) {
        String text = Utf8TextDecoder.decode(source, format(), maxSourceSize);
        int[] lineStarts = lineStarts(text);
        Node document = parser.parse(text);
        var blocks = new ArrayList<ContentBlock>();
        var headings = new ArrayList<Heading>();
        boolean boundary = false;
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof ThematicBreak) {
                boundary = true;
                continue;
            }
            Range range = sourceRange(node, lineStarts, text);
            String content = text.substring(range.start(), range.end());
            if (content.isBlank()) continue;
            ContentBlockType type = blockType(node);
            if (node instanceof org.commonmark.node.Heading heading) {
                headings.removeIf(existing -> existing.level() >= heading.getLevel());
                String title = heading.getFirstChild() instanceof Text literal ? literal.getLiteral().strip() : "";
                // CommonMark 接受空标题，领域 Heading 不接受；保留标记为 TEXT，仍截断对应层级并形成边界。
                if (title.isBlank()) type = ContentBlockType.TEXT;
                else headings.add(new Heading(heading.getLevel(), title));
                boundary = true;
            }
            if (blocks.size() == Utf8TextDecoder.MAX_BLOCKS) {
                throw new IllegalArgumentException("文档内容块数量超过解析限制");
            }
            blocks.add(new ContentBlock(blocks.size(), type, content, List.copyOf(headings),
                    new SourceLocation(range.start(), range.end(), null, null), boundary));
            boundary = false;
        }
        if (blocks.isEmpty()) throw new IllegalArgumentException("文档没有可解析的有效内容");
        return new ParsedDocument(blocks);
    }

    /**
     * 判断 AST 顶层节点对应的内容块语义类型。
     *
     * @param node CommonMark 顶层节点
     * @return 内容块类别（HEADING/CODE/TABLE/TEXT）
     */
    private static ContentBlockType blockType(Node node) {
        if (node instanceof org.commonmark.node.Heading) return ContentBlockType.HEADING;
        if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) return ContentBlockType.CODE;
        if (node instanceof TableBlock) return ContentBlockType.TABLE;
        // 包含 Paragraph、列表、引用、HTML 和 LinkReferenceDefinition；定义节点不是 Block，不能漏掉。
        return ContentBlockType.TEXT;
    }

    /**
     * 预扫描全文所有换行符的位置，计算每行起始字符在全文中的 UTF-16 偏移索引。
     * 校验行数不超过 {@link #MAX_LINES}。
     *
     * @param text 规整后的全文文本
     * @return 长度为行数+1 的数组，索引 1 代表第 1 行的起始偏移
     * @throws IllegalArgumentException 当行数超过 1,000,000 时抛出
     */
    private static int[] lineStarts(String text) {
        int count = 1;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n' && ++count > MAX_LINES) {
                throw new IllegalArgumentException("Markdown 行数超过解析限制");
            }
        }
        int[] starts = new int[count];
        int line = 1;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') starts[line++] = index + 1;
        }
        return starts;
    }

    /**
     * 顶层块占用完整的源行；取首末行的包围区间，保留内部空行、缩进、尾空格及围栏。
     * 不拼接逐行 literal 或使用嵌套子节点的长度（部分消费 tab 时子 span 长度可能对应展开后的列）。
     * 最后一行的分隔 LF 不属于正文，偏移仍严格是全文的 UTF-16 子串区间。
     *
     * @param node AST 块级节点
     * @param starts 行起始索引数组
     * @param text 全文文本
     * @return 块在全文中的字符起止范围
     * @throws IllegalArgumentException 当节点缺少位置 spans 或行号越界时抛出
     */
    private static Range sourceRange(Node node, int[] starts, String text) {
        var spans = node.getSourceSpans();
        if (spans.isEmpty()) throw new IllegalArgumentException("Markdown 内容无法可靠定位");
        int first = spans.getFirst().getLineIndex();
        int last = spans.getLast().getLineIndex();
        if (first < 0 || last < first || last >= starts.length) {
            throw new IllegalArgumentException("Markdown 内容无法可靠定位");
        }
        int end = last + 1 < starts.length ? starts[last + 1] - 1 : text.length();
        return new Range(starts[first], end);
    }

    /**
     * 局部半开字符区间载体 record。
     */
    private record Range(int start, int end) {}
}
