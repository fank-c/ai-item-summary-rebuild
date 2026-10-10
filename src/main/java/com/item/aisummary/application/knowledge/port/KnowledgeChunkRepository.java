package com.item.aisummary.application.knowledge.port;

import com.item.aisummary.domain.knowledge.model.KnowledgeChunk;
import java.util.List;
import java.util.Optional;

public interface KnowledgeChunkRepository {
    /** 插入单片段；批次的原子性由调用它的应用事务提供。 */
    KnowledgeChunk insert(KnowledgeChunk chunk);
    Optional<KnowledgeChunk> findById(String id);
    /** 固定按 sequence_no 升序返回。 */
    List<KnowledgeChunk> findByRevisionId(String revisionId);
    long countByRevisionId(String revisionId);
}
