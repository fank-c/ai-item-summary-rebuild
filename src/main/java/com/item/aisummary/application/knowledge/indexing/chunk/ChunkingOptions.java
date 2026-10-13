package com.item.aisummary.application.knowledge.indexing.chunk;

/**
 * 本次分块的参数配置快照模型。
 *
 * <p>由基础设施层属性绑定后传递给切分策略实现（{@link KnowledgeChunker}），
 * 并不保存到数据库历史版本表中。
 *
 * <p>约束与说明：
 * <ul>
 *   <li>{@code chunkSize}：目标片段尺寸阈值，必须为正整数。</li>
 *   <li>{@code minChunkSizeChars}：标点符号截断的最小字符阈值，必须非负。</li>
 *   <li>{@code minChunkLengthToEmbed}：当前严格要求为 0，防止切分策略静默丢弃短文本导致信息丢失。</li>
 *   <li>{@code maxNumChunks}：文档允许的最大切片总数（父块与子块共同计算），必须为正整数。</li>
 *   <li>{@code keepSeparator}：切分时是否保留标点分隔符。</li>
 *   <li>{@code punctuationMarks}：用于句子断句的中英文标点与换行符。</li>
 * </ul>
 *
 * @param chunkSize 目标 Token/字符尺寸上限
 * @param minChunkSizeChars 标点截断控制的最小字符数
 * @param minChunkLengthToEmbed 允许向量化的最小字符数（当前固定为 0）
 * @param maxNumChunks 文档最大允许生成的片段总数（包含所有角色）
 * @param keepSeparator 是否保留分隔符
 * @param punctuationMarks 断句标点符号集合
 */
public record ChunkingOptions(
        int chunkSize, int minChunkSizeChars, int minChunkLengthToEmbed,
        int maxNumChunks, boolean keepSeparator, String punctuationMarks) {
    public ChunkingOptions {
        if (chunkSize < 1 || minChunkSizeChars < 0 || minChunkLengthToEmbed != 0
                || maxNumChunks < 1 || punctuationMarks == null || punctuationMarks.isEmpty()) {
            throw new IllegalArgumentException("分块配置无效，必须保留所有非空短片段");
        }
    }
}
