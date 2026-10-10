package com.item.aisummary.infrastructure.knowledge.persistence.repository;

import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.domain.knowledge.enums.DocumentIndexStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocument;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeDocumentEntity;
import com.item.aisummary.infrastructure.knowledge.persistence.mapper.KnowledgeDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisKnowledgeDocumentRepository implements KnowledgeDocumentRepository {
    private final KnowledgeDocumentMapper mapper;
    public MyBatisKnowledgeDocumentRepository(KnowledgeDocumentMapper mapper) { this.mapper = mapper; }

    @Override public KnowledgeDocument insert(KnowledgeDocument document) {
        var entity = new KnowledgeDocumentEntity();
        entity.id = document.id(); entity.knowledgeBaseId = document.knowledgeBaseId(); entity.fileId = document.fileId();
        entity.status = document.status(); entity.indexStatus = document.indexStatus().value();
        entity.activeRevisionId = document.activeRevisionId(); entity.lastFailureCode = document.lastFailureCode();
        entity.lastFailureMessage = document.lastFailureMessage(); entity.createAt = document.createAt(); entity.updateAt = document.updateAt();
        try {
            if (mapper.insert(entity) != 1) throw new IllegalStateException("保存知识文档结果不符合预期");
        } catch (DuplicateKeyException exception) {
            throw new KnowledgeConflictException("DOCUMENT_ALREADY_REGISTERED", "该文件已登记到此知识库", exception);
        }
        return toDomain(entity);
    }
    @Override public Optional<KnowledgeDocument> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MyBatisKnowledgeDocumentRepository::toDomain);
    }
    @Override public Optional<KnowledgeDocument> findByKnowledgeBaseAndFile(String knowledgeBaseId, String fileId) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<KnowledgeDocumentEntity>()
            .eq("knowledge_base_id", knowledgeBaseId).eq("file_id", fileId))).map(MyBatisKnowledgeDocumentRepository::toDomain);
    }
    @Override public List<KnowledgeDocument> findByKnowledgeBaseId(String knowledgeBaseId) {
        return mapper.selectList(new QueryWrapper<KnowledgeDocumentEntity>().eq("knowledge_base_id", knowledgeBaseId)
            .orderByAsc("create_at", "id")).stream().map(MyBatisKnowledgeDocumentRepository::toDomain).toList();
    }
    private static KnowledgeDocument toDomain(KnowledgeDocumentEntity entity) {
        return new KnowledgeDocument(entity.id, entity.knowledgeBaseId, entity.fileId, entity.status,
            DocumentIndexStatus.fromValue(entity.indexStatus), entity.activeRevisionId, entity.lastFailureCode,
            entity.lastFailureMessage, entity.createAt, entity.updateAt);
    }
}
