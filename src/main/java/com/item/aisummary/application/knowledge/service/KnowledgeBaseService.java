package com.item.aisummary.application.knowledge.service;

import com.item.aisummary.application.knowledge.port.KnowledgeBaseRepository;
import com.item.aisummary.domain.knowledge.exception.KnowledgeNotFoundException;
import com.item.aisummary.domain.knowledge.model.KnowledgeBase;
import com.item.aisummary.domain.knowledge.rule.KnowledgeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** 内部知识库用例；没有用户唯一性或知识库访问授权规则。 */
public final class KnowledgeBaseService {
    private final KnowledgeBaseRepository repository;
    private final Clock clock;
    public KnowledgeBaseService(KnowledgeBaseRepository repository, Clock clock) {
        this.repository = repository; this.clock = clock;
    }
    public KnowledgeBase create(String name) {
        String validName = KnowledgeRules.requireName(name);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return repository.insert(new KnowledgeBase(UUID.randomUUID().toString().replace("-", ""), validName, 1, now, now));
    }
    public KnowledgeBase findById(String id) {
        return repository.findById(KnowledgeRules.requireId(id, "知识库 ID"))
            .orElseThrow(() -> new KnowledgeNotFoundException("知识库"));
    }
    public List<KnowledgeBase> findAll() { return repository.findAll(); }
    public KnowledgeBase rename(String id, String name) {
        String validName = KnowledgeRules.requireName(name);
        KnowledgeBase existing = findById(id);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (repository.rename(existing.id(), validName, now) != 1) {
            throw new IllegalStateException("修改知识库名称结果不符合预期");
        }
        return new KnowledgeBase(existing.id(), validName, existing.status(), existing.createAt(), now);
    }
}
