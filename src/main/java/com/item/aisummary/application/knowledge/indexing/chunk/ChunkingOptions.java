package com.item.aisummary.application.knowledge.indexing.chunk;

/**
 * 本次分块的参数配置快照模型。
 *
 * <p>由基础设施层属性绑定后传递给切分策略实现（{@link KnowledgeChunker}），
 * 并不保存到数据库历史版本表中。
 *
 * <p>约束与说明：
 * <ul>
 *   <li>{@code chunkSize}：子检索块目标尺寸上限（Token 单位），必须为正整数。</li>
 *   <li>{@code minChunkSizeChars}：标点断句的短块软阈值（字符数），必须非负；用于控制标点断句的最小字符数，低于此阈值尽量不切断，但不丢弃短尾。</li>
 *   <li>{@code minChunkLengthToEmbed}：当前严格要求为 0，防止切分策略静默丢弃短文本导致信息丢失。</li>
 *   <li>{@code maxNumChunks}：文档允许的最大切片总数（包含 CONTEXT 与 RETRIEVAL 所有角色），必须为正整数。</li>
 *   <li>{@code keepSeparator}：换行分隔符保留语义；{@code true} 时原样保留换行，{@code false} 时仅普通文本的 CR/LF 一对一替换为空格（CODE 与 TABLE 始终保留换行），不删除标点且不执行 trim。</li>
 *   <li>{@code punctuationMarks}：用于句子断句的中英文标点与换行符。</li>
 *   <li>{@code parentChunkSize}：父上下文块目标尺寸上限（Token 单位），必须大于或等于 {@code chunkSize}。</li>
 *   <li>{@code chunkOverlap}：子检索块重叠目标尺寸（Token 单位），必须满足 0 &lt;= chunkOverlap &lt; chunkSize。</li>
 * </ul>
 *
 * @param chunkSize 子检索块目标 Token 尺寸上限
 * @param minChunkSizeChars 标点断句控制的最小字符数短块软阈值
 * @param minChunkLengthToEmbed 允许向量化的最小字符数（当前固定为 0）
 * @param maxNumChunks 文档最大允许生成的片段总数（包含所有角色）
 * @param keepSeparator 是否原样保留换行分隔符
 * @param punctuationMarks 断句标点符号集合
 * @param parentChunkSize 父上下文块目标 Token 尺寸上限
 * @param chunkOverlap 子检索块重叠目标 Token 尺寸
 */
public record ChunkingOptions(
        int chunkSize,
        int minChunkSizeChars,
        int minChunkLengthToEmbed,
        int maxNumChunks,
        boolean keepSeparator,
        String punctuationMarks,
        int parentChunkSize,
        int chunkOverlap) {

    public ChunkingOptions {
        if (chunkSize < 1 || minChunkSizeChars < 0 || minChunkLengthToEmbed != 0
                || maxNumChunks < 1 || punctuationMarks == null || punctuationMarks.isEmpty()) {
            throw new IllegalArgumentException("分块配置无效，必须保留所有非空短片段");
        }
        if (parentChunkSize < chunkSize) {
            throw new IllegalArgumentException("父块尺寸必须大于或等于子块尺寸");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("重叠尺寸必须满足 0 <= chunkOverlap < chunkSize");
        }
    }

    /**
     * 向后兼容的 6 参数构造器，默认父块尺寸等于子块尺寸，重叠为 0。
     *
     * @param chunkSize 目标 Token 尺寸上限
     * @param minChunkSizeChars 标点断句控制的最小字符数短块软阈值
     * @param minChunkLengthToEmbed 允许向量化的最小字符数（当前固定为 0）
     * @param maxNumChunks 文档最大允许生成的片段总数（包含所有角色）
     * @param keepSeparator 是否原样保留换行分隔符
     * @param punctuationMarks 断句标点符号集合
     */
    public ChunkingOptions(
            int chunkSize,
            int minChunkSizeChars,
            int minChunkLengthToEmbed,
            int maxNumChunks,
            boolean keepSeparator,
            String punctuationMarks) {
        this(
                chunkSize,
                minChunkSizeChars,
                minChunkLengthToEmbed,
                maxNumChunks,
                keepSeparator,
                punctuationMarks,
                chunkSize,
                0);
    }
}
