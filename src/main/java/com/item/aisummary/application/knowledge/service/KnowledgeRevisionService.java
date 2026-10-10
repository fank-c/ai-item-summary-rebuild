package com.item.aisummary.application.knowledge.service;

import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.domain.knowledge.enums.RevisionStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.exception.KnowledgeNotFoundException;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocumentRevision;
import com.item.aisummary.domain.knowledge.rule.KnowledgeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** 为文档分配 building 版本。 当前服务不读取文件、不执行解析，也不负责发布版本或更新活动版本指针。 */
public final class KnowledgeRevisionService {
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeRevisionRepository revisions;
    private final Clock clock;

    public KnowledgeRevisionService(
            KnowledgeDocumentRepository documents,
            KnowledgeRevisionRepository revisions,
            Clock clock) {
        this.documents = documents;
        this.revisions = revisions;
        this.clock = clock;
    }

    /**
     * 为已存在文档分配下一版本号，创建尚未构建完成的 building 版本。
     *
     * <p>先校验文档 ID 并确认文档存在，再查询当前最大版本号，按 MAX + 1 分配。 无历史版本时从 1 开始；达到 Integer.MAX_VALUE 时明确失败，避免整数溢出。
     * 并发调用可能读到相同最大值，联合唯一约束将竞争转换为版本号冲突，不自动重试。
     *
     * <p>新版本使用 32 位 UUID、chunkCount=0，createdAt 与 startedAt 共用微秒时间。
     * 源摘要、失败信息、完成、发布及清理时间均为空；不读取源文件、不创建片段， 不改变文档索引状态或活动版本指针，也不模拟构建成功。
     *
     * @param knowledgeDocumentId 已存在文档 ID，裁剪后最多 32 个 Java 字符
     * @return 仓储保存的 building 版本
     * @throws IllegalArgumentException 文档 ID 不符合文本规则
     * @throws KnowledgeNotFoundException 文档不存在
     * @throws KnowledgeConflictException 版本号耗尽或并发分配触发唯一冲突
     */
    public KnowledgeDocumentRevision create(String knowledgeDocumentId) {
        String documentId = requireDocument(knowledgeDocumentId);
        int maximum = revisions.findMaxRevisionNumber(documentId);
        if (maximum == Integer.MAX_VALUE) {
            throw new KnowledgeConflictException("REVISION_NUMBER_EXHAUSTED", "文档版本号已达到上限");
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        // 进入 building 时记录 created_at 和 started_at；未处理内容前不填写结果字段。
        KnowledgeDocumentRevision revision =
                new KnowledgeDocumentRevision(
                        UUID.randomUUID().toString().replace("-", ""),
                        documentId,
                        maximum + 1,
                        RevisionStatus.BUILDING,
                        null,
                        0,
                        null,
                        null,
                        null,
                        now,
                        now,
                        null,
                        null,
                        null);
        return revisions.insert(revision);
    }

    /**
     * 查询单个文档版本快照，不加载片段或修改生命周期状态。
     *
     * <p>版本 ID 校验并裁剪后访问仓储；各生命周期状态均可查询。 合法 ID 没有对应记录时抛版本未找到异常，不返回 null。
     *
     * @param id 版本 ID，裁剪后非空白且最多 32 个 Java 字符
     * @return 包含状态、计数和生命周期时间的版本快照
     * @throws IllegalArgumentException ID 不符合文本规则
     * @throws KnowledgeNotFoundException 版本不存在
     */
    public KnowledgeDocumentRevision findById(String id) {
        return revisions
                .findById(KnowledgeRules.requireId(id, "版本 ID"))
                .orElseThrow(() -> new KnowledgeNotFoundException("文档版本"));
    }

    /**
     * 查询指定文档的全部版本，由仓储按版本号升序返回。
     *
     * <p>先校验 ID 并确认文档存在，区分缺失父记录与尚无版本。 不筛选 building、ready 等状态，不发布版本或校验活动版本指针。
     *
     * @param knowledgeDocumentId 文档 ID，查询前裁剪首尾空白
     * @return 升序版本列表；有效文档尚未创建版本时为空列表
     * @throws IllegalArgumentException 文档 ID 不符合文本规则
     * @throws KnowledgeNotFoundException 文档不存在
     */
    public List<KnowledgeDocumentRevision> findByDocumentId(String knowledgeDocumentId) {
        return revisions.findByDocumentId(requireDocument(knowledgeDocumentId));
    }

    /**
     * 规范化文档 ID 并确认文档存在，为创建及版本列表查询共用。
     *
     * <p>仅检查文本 ID 与父记录存在性，不读取源文件、不按文档索引状态过滤， 也不执行知识库权限判断。返回规范化 ID 用于版本关联和最大版本号查询。
     *
     * @throws IllegalArgumentException 文档 ID 不符合文本规则
     * @throws KnowledgeNotFoundException 文档不存在
     */
    private String requireDocument(String value) {
        String id = KnowledgeRules.requireId(value, "文档 ID");
        documents.findById(id).orElseThrow(() -> new KnowledgeNotFoundException("知识文档"));
        return id;
    }
}
