package com.item.aisummary.web.knowledge.response;

import com.item.aisummary.domain.knowledge.model.KnowledgeDocumentRevision;
import java.time.Instant;

/**
 * 文档构建版本结果与生命周期审计的 HTTP 响应记录。
 *
 * <p>用于对外展示文档单次构建版本的所有生命周期状态、片段统计、失败摘要与时间戳；
 * 仅用于版本审计与只读查看，ready 状态不代表当前文档已发布该版本为活动检索版本。
 *
 * @param id 文档版本业务 ID，32 位全局唯一业务标识
 * @param knowledgeDocumentId 所属知识文档 ID
 * @param revisionNumber 同一知识文档内严格单调递增的版本序号，从 1 开始
 * @param revisionStatus 版本生命周期状态字符串，包含 building（构建中）、ready（构建就绪）、failed（构建失败）、superseded（已被新版本替代）
 * @param sourceSha256 构建时实际读取并校验的源文件 SHA-256 摘要；初始阶段未计算时为 null
 * @param chunkCount 当前版本包含的全部切片片段总数（单位为个，包含 CONTEXT 上下文块与 RETRIEVAL 检索块，不等于向量数）
 * @param failureStage 构建失败发生的处理阶段（真实阶段包含 SOURCE_READ、PARSE、CHUNK、CHUNK_SAVE、VECTOR_WRITE、PUBLISH、CLEANUP）；未失败时为 null
 * @param failureCode 构建失败业务错误编码；未失败时为 null
 * @param failureMessage 构建失败的脱敏摘要说明；未失败时为 null
 * @param createdAt 版本记录创建时间，按 UTC 微秒精度记录
 * @param startedAt 进入构建流程并开始处理的时间，按 UTC 微秒精度记录
 * @param completedAt 构建流程全部结束的时间（成功或失败）；尚未完成构建时为 null
 * @param publishedAt 该版本正式发布为文档当前生效活动版本的时间；未发布或失败时为 null
 * @param cleanedAt 旧版本派生数据（如外部向量库索引）清理完成的时间；尚未清理、无需清理或清理未确认时为 null
 */
public record KnowledgeRevisionResponse(
        String id,
        String knowledgeDocumentId,
        int revisionNumber,
        String revisionStatus,
        String sourceSha256,
        int chunkCount,
        String failureStage,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant publishedAt,
        Instant cleanedAt) {

    /**
     * 将文档版本领域实体转换为 HTTP 响应记录。
     *
     * @param revision 文档版本领域实体
     * @return 映射后的文档版本 HTTP 响应记录
     */
    public static KnowledgeRevisionResponse from(KnowledgeDocumentRevision revision) {
        return new KnowledgeRevisionResponse(
                revision.id(),
                revision.knowledgeDocumentId(),
                revision.revisionNumber(),
                revision.revisionStatus().value(),
                revision.sourceSha256(),
                revision.chunkCount(),
                revision.failureStage(),
                revision.failureCode(),
                revision.failureMessage(),
                revision.createdAt(),
                revision.startedAt(),
                revision.completedAt(),
                revision.publishedAt(),
                revision.cleanedAt());
    }
}
