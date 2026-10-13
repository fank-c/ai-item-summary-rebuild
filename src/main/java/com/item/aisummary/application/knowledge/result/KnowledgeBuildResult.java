package com.item.aisummary.application.knowledge.result;

/**
 * 知识库构建成功完成的返回结果模型。
 *
 * <p>表示文档版本发布成功后的事实数据：
 * <ul>
 *   <li>{@code chunkCount}：保存到 MySQL 的全部片段总数（包含 CONTEXT 与 RETRIEVAL）。</li>
 *   <li>{@code retrievalChunkCount}：已向量化写入向量库的检索片段数（仅 RETRIEVAL）。</li>
 *   <li>{@code oldRevisionCleanupCompleted}：旧活动版本的历史向量是否在外部向量库完成物理删除。
 *       若旧向量删除失败，新活动版本依然发布成功，该字段为 false 并产生告警，由运维核对。</li>
 * </ul>
 *
 * @param documentId 知识文档 ID
 * @param revisionId 新发布并生效的文档版本 ID
 * @param chunkCount 该版本包含的全部片段数量（CONTEXT + RETRIEVAL）
 * @param retrievalChunkCount 实际写入向量库的检索片段数量
 * @param oldRevisionCleanupCompleted 旧版本向量数据是否已完成清理
 */
public record KnowledgeBuildResult(
        String documentId, String revisionId, int chunkCount,
        int retrievalChunkCount, boolean oldRevisionCleanupCompleted) {}
