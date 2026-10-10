package com.item.aisummary.application.knowledge.port;

import com.item.aisummary.domain.knowledge.model.KnowledgeBase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRepository {
    KnowledgeBase insert(KnowledgeBase base);
    Optional<KnowledgeBase> findById(String id);
    List<KnowledgeBase> findAll();
    /** 只修改名称和更新时间，返回影响行数。 */
    int rename(String id, String name, Instant updateAt);
}
