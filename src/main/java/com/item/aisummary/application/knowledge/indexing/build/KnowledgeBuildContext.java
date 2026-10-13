package com.item.aisummary.application.knowledge.indexing.build;

import com.item.aisummary.domain.knowledge.model.KnowledgeDocument;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocumentRevision;

/**
 * 构建开始短事务的上下文结果模型。
 *
 * <p>封装在 {@link KnowledgeBuildLifecycleService#begin}
 * 短事务中加锁确认的知识文档与其新创建的 building 版本，同时持有构建开始时的活动版本快照（{@code active_revision_id}）。
 *
 * <p>该快照在最终发布阶段（{@link KnowledgeBuildLifecycleService#publish}）
 * 用于严格的一致性比对：若在耗时的解析与向量化期间活动版本被并发构建改变，发布短事务将拒绝切换，防止覆盖其他构建的结果。
 *
 * @param document 构建关联的知识文档实体快照
 * @param revision 新创建的处于 BUILDING 状态的文档版本实体
 */
public record KnowledgeBuildContext(KnowledgeDocument document, KnowledgeDocumentRevision revision) {
    public KnowledgeBuildContext {
        if (document == null || revision == null
                || !document.id().equals(revision.knowledgeDocumentId())) {
            throw new IllegalArgumentException("构建上下文的文档和版本归属无效");
        }
    }

    /**
     * 获取构建开始时文档的活动版本 ID 快照（可为 null）。
     *
     * @return 构建启动时的活动版本 ID
     */
    public String previousActiveRevisionId() { return document.activeRevisionId(); }
}
