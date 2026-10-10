package com.item.aisummary.application.knowledge.service;

import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.domain.knowledge.enums.RevisionStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.exception.KnowledgeNotFoundException;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocumentRevision;
import com.item.aisummary.domain.knowledge.rule.KnowledgeRules;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** 分配 building 版本；没有解析任务、发布或活动版本更新。 */
public final class KnowledgeRevisionService {
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeRevisionRepository revisions;
    private final Clock clock;
    public KnowledgeRevisionService(KnowledgeDocumentRepository documents, KnowledgeRevisionRepository revisions, Clock clock) {
        this.documents = documents; this.revisions = revisions; this.clock = clock;
    }
    public KnowledgeDocumentRevision create(String knowledgeDocumentId) {
        String documentId = requireDocument(knowledgeDocumentId);
        int maximum = revisions.findMaxRevisionNumber(documentId);
        if (maximum == Integer.MAX_VALUE) {
            throw new KnowledgeConflictException("REVISION_NUMBER_EXHAUSTED", "文档版本号已达到上限");
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        // MAX + 1 可能竞争；insert 的联合唯一约束将冲突显式报告，不进行重试。
        // started_at 是进入 building 的时间；未读取内容前不填写摘要或成功结果。
        return revisions.insert(new KnowledgeDocumentRevision(UUID.randomUUID().toString().replace("-", ""),
            documentId, maximum + 1, RevisionStatus.BUILDING, null, 0, null, null, null, now, now, null, null, null));
    }
    public KnowledgeDocumentRevision findById(String id) {
        return revisions.findById(KnowledgeRules.requireId(id, "版本 ID"))
            .orElseThrow(() -> new KnowledgeNotFoundException("文档版本"));
    }
    public List<KnowledgeDocumentRevision> findByDocumentId(String knowledgeDocumentId) {
        return revisions.findByDocumentId(requireDocument(knowledgeDocumentId));
    }
    private String requireDocument(String value) {
        String id = KnowledgeRules.requireId(value, "文档 ID");
        documents.findById(id).orElseThrow(() -> new KnowledgeNotFoundException("知识文档"));
        return id;
    }
}
