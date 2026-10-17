package com.item.aisummary.web.knowledge.response;

import com.item.aisummary.domain.knowledge.model.KnowledgeDocument;
import java.time.Instant;

/**
 * 知识文档登记关系与生命周期索引状态的 HTTP 响应记录。
 *
 * <p>用于对外暴露源文件在知识库中的登记信息、生命周期索引状态、当前生效的活动版本及最近一次失败摘要；
 * 当前阶段尚未实现检索功能，不直接展开文档全文、版本历史或切片片段。
 *
 * @param id 知识文档业务 ID，32 位全局唯一业务标识
 * @param knowledgeBaseId 所属知识库 ID，通过逻辑关联维护
 * @param fileId 已上传源文件的资源 ID，关联已校验归属的已上传文件
 * @param status 文档业务可用状态，当前 1 表示正常可用
 * @param indexStatus 文档索引生命周期状态字符串，包含 pending（等待构建）、indexed（已完成构建与发布，当前未实现检索功能）、failed（构建失败）、removed（已移除）
 * @param activeRevisionId 当前生效的活动版本 ID；首次构建发布前为 null，失败重建时保留上一次成功版本
 * @param lastFailureCode 最近一次构建失败的业务错误编码；未发生构建失败或初始登记时为 null
 * @param lastFailureMessage 最近一次构建失败的脱敏摘要说明；未发生构建失败或初始登记时为 null
 * @param createAt 文档登记创建时间，按 UTC 微秒精度记录
 * @param updateAt 文档记录最后更新时间，按 UTC 微秒精度记录
 */
public record KnowledgeDocumentResponse(
        String id,
        String knowledgeBaseId,
        String fileId,
        int status,
        String indexStatus,
        String activeRevisionId,
        String lastFailureCode,
        String lastFailureMessage,
        Instant createAt,
        Instant updateAt) {

    /**
     * 将知识文档领域实体转换为 HTTP 响应记录。
     *
     * @param document 知识文档领域实体
     * @return 映射后的知识文档 HTTP 响应记录
     */
    public static KnowledgeDocumentResponse from(KnowledgeDocument document) {
        return new KnowledgeDocumentResponse(
                document.id(),
                document.knowledgeBaseId(),
                document.fileId(),
                document.status(),
                document.indexStatus().value(),
                document.activeRevisionId(),
                document.lastFailureCode(),
                document.lastFailureMessage(),
                document.createAt(),
                document.updateAt());
    }
}
