package com.item.aisummary.application.knowledge.port;

import com.item.aisummary.domain.knowledge.model.KnowledgeDocumentRevision;
import java.util.List;
import java.util.Optional;

public interface KnowledgeRevisionRepository {
    KnowledgeDocumentRevision insert(KnowledgeDocumentRevision revision);
    Optional<KnowledgeDocumentRevision> findById(String id);
    List<KnowledgeDocumentRevision> findByDocumentId(String knowledgeDocumentId);
    /** 无版本时返回 0；读取与插入不提供原子分配，联合唯一约束兜底。 */
    int findMaxRevisionNumber(String knowledgeDocumentId);
    /** 必须在 READ_COMMITTED 事务中调用；锁保持到事务结束，用于串行化同版本片段批次。 */
    Optional<KnowledgeDocumentRevision> findByIdForUpdate(String id);
    /** 仅更新 building 版本的 chunk_count，不修改状态、摘要和生命周期时间。 */
    int updateChunkCount(String id, int chunkCount);
}
