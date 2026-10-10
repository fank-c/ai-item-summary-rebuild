package com.item.aisummary.application.knowledge.service;

import com.item.aisummary.application.auth.ActorUserIdRules;
import com.item.aisummary.application.auth.AuthenticationRequiredException;
import com.item.aisummary.application.file.content.FileContentService;
import com.item.aisummary.application.knowledge.port.KnowledgeBaseRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.domain.knowledge.enums.DocumentIndexStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.exception.KnowledgeNotFoundException;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocument;
import com.item.aisummary.domain.knowledge.rule.KnowledgeRules;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** 只登记文件关系；文件归属检查不代表知识库访问授权。 */
public final class KnowledgeDocumentService {
    private final KnowledgeBaseRepository bases;
    private final KnowledgeDocumentRepository documents;
    private final FileContentService files;
    private final Clock clock;
    public KnowledgeDocumentService(KnowledgeBaseRepository bases, KnowledgeDocumentRepository documents,
                                     FileContentService files, Clock clock) {
        this.bases = bases; this.documents = documents; this.files = files; this.clock = clock;
    }
    public KnowledgeDocument register(String actorUserId, String knowledgeBaseId, String fileId) {
        if (actorUserId == null || actorUserId.isBlank()) throw new AuthenticationRequiredException();
        ActorUserIdRules.requireValid(actorUserId);
        String baseId = requireBase(knowledgeBaseId);
        String sourceFileId = KnowledgeRules.requireId(fileId, "文件 ID");
        // 此合同只查询元数据；不打开正文，不绕过文件模块访问其 Mapper。
        files.requireOwnedProcessable(actorUserId, sourceFileId);
        if (documents.findByKnowledgeBaseAndFile(baseId, sourceFileId).isPresent()) {
            throw new KnowledgeConflictException("DOCUMENT_ALREADY_REGISTERED", "该文件已登记到此知识库");
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return documents.insert(new KnowledgeDocument(UUID.randomUUID().toString().replace("-", ""),
            baseId, sourceFileId, 1, DocumentIndexStatus.PENDING, null, null, null, now, now));
    }
    public KnowledgeDocument findById(String id) {
        return documents.findById(KnowledgeRules.requireId(id, "文档 ID"))
            .orElseThrow(() -> new KnowledgeNotFoundException("知识文档"));
    }
    public List<KnowledgeDocument> findByKnowledgeBaseId(String knowledgeBaseId) {
        return documents.findByKnowledgeBaseId(requireBase(knowledgeBaseId));
    }
    private String requireBase(String value) {
        String id = KnowledgeRules.requireId(value, "知识库 ID");
        bases.findById(id).orElseThrow(() -> new KnowledgeNotFoundException("知识库"));
        return id;
    }
}
