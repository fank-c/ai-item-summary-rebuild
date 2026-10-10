package com.item.aisummary.application.knowledge.port;

import com.item.aisummary.domain.knowledge.model.KnowledgeDocument;
import java.util.List;
import java.util.Optional;

public interface KnowledgeDocumentRepository {
    /** 唯一约束失败抛业务冲突，其他数据库异常继续传播。 */
    KnowledgeDocument insert(KnowledgeDocument document);
    Optional<KnowledgeDocument> findById(String id);
    Optional<KnowledgeDocument> findByKnowledgeBaseAndFile(String knowledgeBaseId, String fileId);
    List<KnowledgeDocument> findByKnowledgeBaseId(String knowledgeBaseId);
}
