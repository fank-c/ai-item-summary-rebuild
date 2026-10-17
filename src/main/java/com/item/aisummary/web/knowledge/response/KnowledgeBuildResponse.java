package com.item.aisummary.web.knowledge.response;

import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildResult;

/**
 * 知识文档同步构建完成后的 HTTP 响应记录。
 *
 * <p>用于对外返回文档版本发布成功后的事实数据，包含生成的活动版本 ID、
 * 持久化片段总数、向量化写入数量以及旧版本派生数据清理状态。
 *
 * @param documentId 知识文档业务 ID
 * @param revisionId 本次构建生成并已正式发布生效的文档版本 ID
 * @param chunkCount 本版本保存到数据库的全部片段总数（单位为个，包含 CONTEXT 上下文块与 RETRIEVAL 检索块）
 * @param retrievalChunkCount 实际写入外部向量库的检索片段数量（单位为个，仅包含 RETRIEVAL 检索块）
 * @param oldRevisionCleanupCompleted 旧版本向量数据是否已完成清理；若本次构建为首次构建（无旧版本）则为 true；若存在旧版本且旧向量删除成功且清理事实写库成功则为 true；若旧向量删除失败或清理事实写库失败则为 false（服务端保留告警日志）
 */
public record KnowledgeBuildResponse(
        String documentId,
        String revisionId,
        int chunkCount,
        int retrievalChunkCount,
        boolean oldRevisionCleanupCompleted) {

    /**
     * 将应用层构建完成返回的结果模型转换为 HTTP 响应记录。
     *
     * @param result 应用层构建完成返回的结果模型
     * @return 映射后的构建结果 HTTP 响应记录
     */
    public static KnowledgeBuildResponse from(KnowledgeBuildResult result) {
        return new KnowledgeBuildResponse(
                result.documentId(),
                result.revisionId(),
                result.chunkCount(),
                result.retrievalChunkCount(),
                result.oldRevisionCleanupCompleted());
    }
}
