package com.item.aisummary.infrastructure.knowledge.indexing.parse;

import com.item.aisummary.application.knowledge.indexing.parse.ContentBlock;
import com.item.aisummary.application.knowledge.indexing.parse.ContentBlockType;
import com.item.aisummary.application.knowledge.indexing.parse.KnowledgeDocumentParser;
import com.item.aisummary.application.knowledge.indexing.parse.ParsedDocument;
import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.application.knowledge.indexing.source.PreparedSource;
import com.item.aisummary.domain.knowledge.value.SourceLocation;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯文本（TXT）文档格式解析策略实现。
 *
 * <p>遵循如下解析规则：
 * <ul>
 *   <li>仅支持 {@link FileFormat#TXT} 格式，委托 {@link Utf8TextDecoder} 执行严格 UTF-8 解码与换行规整。</li>
 *   <li>空行分段：由连续换行或包含空白字符（涵盖普通空白与 {@code isSpaceChar} 如 NBSP）的行作为自然段落分隔符。</li>
 *   <li>段内单换行原样保留，不裁剪正文缩进与行尾尾随空格；段外分隔换行不计入内容块。</li>
 *   <li>精确计算 UTF-16 字符偏移，指向规整后全文的子串；页码与标题路径保持为 {@code null}，不预先推测排版。</li>
 *   <li>所有块类别均为 {@link ContentBlockType#TEXT}，无硬边界（{@code boundaryBefore = false}），序号从 0 连续递增。</li>
 *   <li>空白文档（含纯空白、纯 NBSP 或 0 字节）明确拒绝。</li>
 *   <li>输出内容块数量受控于固定的 {@link Utf8TextDecoder#MAX_BLOCKS}（100,000）安全预算，超限明确拒绝。</li>
 *   <li>解析过程不生成业务 ID 或估算 Token 数。</li>
 * </ul>
 */
public final class TextKnowledgeDocumentParser implements KnowledgeDocumentParser {
    private final long maxSourceSize;

    /**
     * 构造 TXT 解析器。
     *
     * @param maxSourceSize 单个源文件允许的最大字节预算（必须为正且小于 Integer.MAX_VALUE）
     * @throws IllegalArgumentException 当大小预算非法时抛出
     */
    public TextKnowledgeDocumentParser(long maxSourceSize) {
        if (maxSourceSize < 1 || maxSourceSize >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("源文件大小上限必须为正且小于 Integer.MAX_VALUE 字节");
        }
        this.maxSourceSize = maxSourceSize;
    }

    @Override
    public FileFormat format() {
        return FileFormat.TXT;
    }

    @Override
    public ParsedDocument parse(PreparedSource source) {
        String fullText = Utf8TextDecoder.decode(source, FileFormat.TXT, maxSourceSize);

        if (fullText.isEmpty() || isAllBlank(fullText)) {
            throw new IllegalArgumentException("文档内容不能为空白");
        }

        List<ContentBlock> blocks = new ArrayList<>();
        int length = fullText.length();
        int pos = 0;
        int paraStart = -1;
        int paraEnd = -1;
        int ordinal = 0;

        while (pos < length) {
            int nextNewline = fullText.indexOf('\n', pos);
            int lineEnd = (nextNewline == -1) ? length : nextNewline;
            boolean blank = isBlankLine(fullText, pos, lineEnd);

            if (!blank) {
                if (paraStart == -1) {
                    paraStart = pos;
                }
                paraEnd = lineEnd;
            } else {
                if (paraStart != -1) {
                    if (blocks.size() >= Utf8TextDecoder.MAX_BLOCKS) {
                        throw new IllegalArgumentException("解析内容块数量超过最大限制: " + Utf8TextDecoder.MAX_BLOCKS);
                    }
                    blocks.add(createBlock(ordinal++, fullText, paraStart, paraEnd));
                    paraStart = -1;
                    paraEnd = -1;
                }
            }

            if (nextNewline == -1) {
                break;
            }
            pos = nextNewline + 1;
        }

        if (paraStart != -1) {
            if (blocks.size() >= Utf8TextDecoder.MAX_BLOCKS) {
                throw new IllegalArgumentException("解析内容块数量超过最大限制: " + Utf8TextDecoder.MAX_BLOCKS);
            }
            blocks.add(createBlock(ordinal++, fullText, paraStart, paraEnd));
        }

        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("文档内容不能为空白");
        }

        return new ParsedDocument(blocks);
    }

    private static boolean isAllBlank(String text) {
        if (text.isBlank()) {
            return true;
        }
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isBlankLine(String text, int start, int end) {
        if (start >= end) {
            return true;
        }
        for (int i = start; i < end; ) {
            int codePoint = text.codePointAt(i);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    private static ContentBlock createBlock(int ordinal, String fullText, int start, int end) {
        String blockText = fullText.substring(start, end);
        var location = new SourceLocation(start, end, null, null);
        return new ContentBlock(ordinal, ContentBlockType.TEXT, blockText, null, location, false);
    }
}
