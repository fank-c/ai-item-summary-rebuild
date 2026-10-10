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

/**
 * 知识文档版本（Revision）基础服务。
 *
 * <p>负责文档版本记录的基础增查：递增分配 building 版本号及查询历史版本列表。
 */
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
     * 为指定文档分配下一递增版本号（MAX + 1），并创建 building 状态的版本记录。
     *
     * @param knowledgeDocumentId 知识文档 ID
     * @return 新建的 building 版本实体
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
     * 根据版本唯一标识查询版本元数据快照。
     *
     * <p>仅查询单条版本元数据，不加载其下的片段列表，不修改其生命周期状态。
     *
     * @param id 版本唯一标识（32 位 UUID）
     * @return 匹配的文档版本实体快照
     * @throws IllegalArgumentException 版本 ID 格式非法
     * @throws KnowledgeNotFoundException 版本不存在
     */
    public KnowledgeDocumentRevision findById(String id) {
        return revisions
                .findById(KnowledgeRules.requireId(id, "版本 ID"))
                .orElseThrow(() -> new KnowledgeNotFoundException("文档版本"));
    }

    /**
     * 查询指定知识文档下的全部版本列表。
     *
     * <p>查询前校验文档存在性；返回列表固定由底层仓储按版本号（{@code revision_number}）升序排列，
     * 包含所有状态（building、ready、superseded、failed）的历史版本记录。
     *
     * @param knowledgeDocumentId 知识文档 ID（32 位 UUID）
     * @return 升序排序的版本实体列表；若文档尚无任何版本则返回空列表
     * @throws IllegalArgumentException 文档 ID 格式非法
     * @throws KnowledgeNotFoundException 知识文档不存在
     */
    public List<KnowledgeDocumentRevision> findByDocumentId(String knowledgeDocumentId) {
        return revisions.findByDocumentId(requireDocument(knowledgeDocumentId));
    }

    /**
     * 校验文档 ID 格式并确认该文档在数据库中真实存在。
     *
     * @param value 原始文档 ID 字符串
     * @return 规范化后的文档 ID
     * @throws IllegalArgumentException 文档 ID 为空或格式非法
     * @throws KnowledgeNotFoundException 知识文档记录不存在
     */
    private String requireDocument(String value) {
        String id = KnowledgeRules.requireId(value, "文档 ID");
        documents.findById(id).orElseThrow(() -> new KnowledgeNotFoundException("知识文档"));
        return id;
    }
}
