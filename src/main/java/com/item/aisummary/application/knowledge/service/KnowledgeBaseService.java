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
 * 知识库自身信息的内部用例。
 *
 * <p>当前不包含用户唯一性和知识库访问授权规则，名称也允许在不同记录之间重复。
 */
public final class KnowledgeBaseService {
    private final KnowledgeBaseRepository repository;
    private final Clock clock;

    public KnowledgeBaseService(KnowledgeBaseRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 创建只保存自身信息的知识库，允许不同记录使用相同名称。
     *
     * <p>先将名称 trim 并检查非空白及最多 100 个 Java 字符，再生成 32 位 UUID。 新记录 status=1，创建与更新时间共用注入时钟的微秒时间。
     * 不查询用户、不绑定所有者，也不将缺少所有者字段解释为公开访问授权。
     *
     * @param name 知识库名称，首尾空白会被裁剪
     * @return 仓储保存的知识库记录
     * @throws IllegalArgumentException 名称为空白或裁剪后超过长度上限
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
     * 根据规范化后的 ID 查询知识库详情。
     *
     * <p>先检查 ID 非空白且裁剪后不超过 32 个 Java 字符，再访问仓储。 格式合法但没有记录时抛内部资源未找到异常，不返回 null，不执行权限判断。
     *
     * @param id 知识库 ID，查询前裁剪首尾空白
     * @return 仓储返回的知识库快照
     * @throws IllegalArgumentException ID 缺失、为空白或超过长度上限
     * @throws KnowledgeNotFoundException 知识库不存在
     */
    public KnowledgeBase findById(String id) {
        return repository
                .findById(KnowledgeRules.requireId(id, "知识库 ID"))
                .orElseThrow(() -> new KnowledgeNotFoundException("知识库"));
    }

    /**
     * 查询全部知识库，直接保留仓储定义的稳定顺序。
     *
     * <p>本用例不增加分页、账户归属或权限过滤，也不按名称合并同名记录。 查询结果仅表示当前持久化数据，不代表调用者拥有每个知识库的访问权限。
     *
     * @return 知识库列表；没有记录时为空列表
     */
    public List<KnowledgeBase> findAll() {
        return repository.findAll();
    }

    /**
     * 修改已存在知识库的名称，保留其 ID、状态和创建时间。
     *
     * <p>先规范化并检查新名称，再查询知识库；因此无效名称先于记录不存在被拒绝。 仓储只写 name 和微秒精度 updateAt，必须影响一行。
     * 响应由已读取快照与本次新名称组合，不重新查询，不提供完整并发覆盖治理。
     *
     * @param id 待改名知识库 ID，查询时裁剪首尾空白
     * @param name 新名称，裁剪后非空白且最多 100 个 Java 字符，允许与其他库同名
     * @return 保留原状态和创建时间的改名快照
     * @throws IllegalArgumentException 名称或 ID 不符合文本规则
     * @throws KnowledgeNotFoundException 知识库不存在
     * @throws IllegalStateException 仓储改名的影响行数不是一行
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
