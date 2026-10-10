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

/**
 * 知识库（KnowledgeBase）元数据管理服务。
 *
 * <p>负责知识库实体的创建、查询与重命名。
 */
public final class KnowledgeBaseService {
    private final KnowledgeBaseRepository repository;
    private final Clock clock;

    public KnowledgeBaseService(KnowledgeBaseRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 创建知识库（支持同名库，生成 32 位 UUID，初始化 status=1）。
     *
     * @param name 知识库名称（裁剪后 1~100 字符）
     * @return 持久化后的知识库实体
     */
    public KnowledgeBase create(String name) {
        String validName = KnowledgeRules.requireName(name);
        Instant now = now();
        KnowledgeBase base =
                new KnowledgeBase(
                        UUID.randomUUID().toString().replace("-", ""), validName, 1, now, now);
        return repository.insert(base);
    }

    /**
     * 根据 ID 查询知识库详情。
     *
     * @param id 知识库 ID
     * @return 知识库实体
     */
    public KnowledgeBase findById(String id) {
        return repository
                .findById(KnowledgeRules.requireId(id, "知识库 ID"))
                .orElseThrow(() -> new KnowledgeNotFoundException("知识库"));
    }

    /**
     * 查询全部知识库列表。
     *
     * @return 知识库实体列表
     */
    public List<KnowledgeBase> findAll() {
        return repository.findAll();
    }

    /**
     * 修改知识库名称，保留其 ID、状态和创建时间。
     *
     * @param id 知识库 ID
     * @param name 新名称（裁剪后 1~100 字符）
     * @return 更新后的知识库实体快照
     */
    public KnowledgeBase rename(String id, String name) {
        String validName = KnowledgeRules.requireName(name);
        KnowledgeBase existing = findById(id);
        Instant now = now();
        if (repository.rename(existing.id(), validName, now) != 1) {
            throw new IllegalStateException("修改知识库名称结果不符合预期");
        }
        return new KnowledgeBase(
                existing.id(), validName, existing.status(), existing.createAt(), now);
    }

    /**
     * 使用共享 Clock 生成知识库写入时间，并截断到数据库微秒精度。
     *
     * <p>保留 Instant 的绝对时刻，不受服务器本地时区影响； 同一次创建或改名使用该时间构建写入参数和返回快照。
     *
     * @return 微秒精度的当前时刻
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
