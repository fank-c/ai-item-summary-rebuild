package com.item.aisummary.infrastructure.knowledge.indexing.chunk;

import com.item.aisummary.application.knowledge.indexing.chunk.ChunkDraft;
import com.item.aisummary.application.knowledge.indexing.parse.ContentBlock;
import com.item.aisummary.application.knowledge.indexing.parse.ContentBlockType;
import com.item.aisummary.domain.knowledge.value.SourceLocation;
import java.util.ArrayList;
import java.util.List;

/**
 * 有界逻辑连续内容块的文本合并与来源位置双向映射载体。
 *
 * <p>处理连续同标题路径、无硬边界的一组内容块（logical run）：
 * <ul>
 *   <li>合并拼接：块之间使用固定分隔符连接（{@code keepSeparator=true} 时为 {@code "\n\n"}，{@code false} 时为 {@code "  "}）。
 *       插入的分隔符在原文中没有物理对应，因此跨越两个块的切片区间无法可靠映射全文偏移，此时偏移保守置为 null。</li>
 *   <li>结构保护：代码块（CODE）与表格（TABLE）始终保留其原生内部换行与格式；普通文本在 {@code keepSeparator=false} 时将 CR/LF 一对一替换为空格，不 trim 也不删除标点。</li>
 *   <li>来源映射：记录每个输入块在合并文本中的局部字符起止区间（{@code Piece}）。切分产出区间时，使用二分查找精确定位被触及的原始内容块，
 *       若切片恰好落在单个原样等长的文本块内，能够精确推导全文 UTF-16 偏移；页码仅在完全属于单页或覆盖整个块时才予以保留。</li>
 * </ul>
 */
final class SourceMappedText {
    private final String text;
    private final List<Piece> pieces;
    private final boolean lineOnly;

    /**
     * 根据输入的内容块列表构建带有来源映射的拼接文本。
     *
     * @param blocks 逻辑连续的内容块列表
     * @param keepSeparator 是否原样保留普通文本换行
     */
    SourceMappedText(List<ContentBlock> blocks, boolean keepSeparator) {
        var builder = new StringBuilder();
        var mapping = new ArrayList<Piece>();
        lineOnly = blocks.getFirst().type() == ContentBlockType.CODE || blocks.getFirst().type() == ContentBlockType.TABLE;
        for (var block : blocks) {
            JTokkitTokenBudget.requireValidUnicode(block.text());
            if (!builder.isEmpty()) builder.append(keepSeparator ? "\n\n" : "  ");
            int start = builder.length();
            // false 只做等长换行替换，不 trim、不删除标点；结构块始终保留排版。
            builder.append(keepSeparator || lineOnly ? block.text() : block.text().replace('\r', ' ').replace('\n', ' '));
            mapping.add(new Piece(start, builder.length(), block));
        }
        text = builder.toString();
        pieces = List.copyOf(mapping);
    }

    /** 获取拼接后的全文文本。 */
    String text() { return text; }

    /** 是否为仅允许按行切分的结构化块（CODE 或 TABLE）。 */
    boolean lineOnly() { return lineOnly; }

    /**
     * 根据切分区间提取正文并构造对应的片段草稿对象（ChunkDraft）。
     *
     * @param range 局部切分字符区间
     * @param tokens 本地分词器工具
     * @return 包含正文、标题路径、Token 数与可靠来源位置的 ChunkDraft
     * @throws IllegalArgumentException 当区间未触及任何有效来源块时抛出
     */
    ChunkDraft draft(TokenRangeSplitter.Range range, JTokkitTokenBudget tokens) {
        String content = text.substring(range.start(), range.end());
        // 按局部坐标二分定位，避免每个检索块都扫描文档的所有输入块。
        int low = 0;
        int high = pieces.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (pieces.get(middle).end() <= range.start()) low = middle + 1;
            else high = middle;
        }
        int end = low;
        while (end < pieces.size() && pieces.get(end).start() < range.end()) end++;
        var touched = pieces.subList(low, end);
        if (touched.isEmpty()) throw new IllegalArgumentException("片段没有有效正文来源");
        // logical run 在路径变化处隔离，所以所有 touched 来源路径相同；null 与 [] 保持区别。
        return new ChunkDraft(content, touched.getFirst().block().headingPath(), tokens.count(content), location(range, touched));
    }

    /**
     * 计算切分区间在源文件全文中的精确位置。
     *
     * <p>仅当切片完全落在单一原始等长内容块内部时，才推导子偏移；若跨越了块间隙或块内容非等长，偏移保守设为 null。
     * 页码仅在单页可靠或完整跨越时保留，不部分猜页。
     */
    private static SourceLocation location(TokenRangeSplitter.Range range, List<Piece> touched) {
        Integer startOffset = null;
        Integer endOffset = null;
        if (touched.size() == 1) {
            Piece piece = touched.getFirst();
            var source = piece.block().source();
            if (source != null && source.startOffset() != null
                    && range.start() >= piece.start() && range.end() <= piece.end()) {
                if (range.start() == piece.start() && range.end() == piece.end()) {
                    startOffset = source.startOffset();
                    endOffset = source.endOffset();
                } else if (source.endOffset() - source.startOffset() == piece.block().text().length()) {
                    // 合同中的精确块位置与原样等长文本才能按 UTF-16 局部位置推导；非等长只保留完整块范围。
                    startOffset = source.startOffset() + range.start() - piece.start();
                    endOffset = source.startOffset() + range.end() - piece.start();
                }
            }
        }
        Integer pageStart = null;
        Integer pageEnd = null;
        boolean pagesKnown = true;
        for (Piece piece : touched) {
            var source = piece.block().source();
            boolean whole = range.start() <= piece.start() && range.end() >= piece.end();
            if (source == null || source.pageStart() == null
                    || (!whole && !source.pageStart().equals(source.pageEnd()))) {
                pagesKnown = false;
                break;
            }
            pageStart = pageStart == null ? source.pageStart() : Math.min(pageStart, source.pageStart());
            pageEnd = pageEnd == null ? source.pageEnd() : Math.max(pageEnd, source.pageEnd());
        }
        if (!pagesKnown) { pageStart = null; pageEnd = null; }
        return startOffset == null && pageStart == null ? null : new SourceLocation(startOffset, endOffset, pageStart, pageEnd);
    }

    /** 记录单个块在拼接文本中的起止字符偏移映射。 */
    private record Piece(int start, int end, ContentBlock block) {}
}
