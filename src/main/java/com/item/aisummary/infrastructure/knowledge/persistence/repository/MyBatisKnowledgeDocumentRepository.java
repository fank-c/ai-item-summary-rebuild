package com.item.aisummary.infrastructure.knowledge.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.domain.knowledge.enums.DocumentIndexStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocument;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeDocumentEntity;
import com.item.aisummary.infrastructure.knowledge.persistence.mapper.KnowledgeDocumentMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** 知识文档表的 MyBatis 持久化适配器。 */
@Repository
public class MyBatisKnowledgeDocumentRepository implements KnowledgeDocumentRepository {
    private final KnowledgeDocumentMapper mapper;

    public MyBatisKnowledgeDocumentRepository(KnowledgeDocumentMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 插入知识库文档记录。
     *
     * <p>若触发唯一约束冲突，将其包装为 DOCUMENT_ALREADY_REGISTERED 业务冲突异常。
     *
     * @param document 知识文档领域实体
     * @return 插入成功后的领域实体
     * @throws KnowledgeConflictException 唯一约束冲突（文件已在该知识库登记）
     * @throws IllegalStateException 受影响行数不为 1
     */
    @Override
    public KnowledgeDocument insert(KnowledgeDocument document) {
        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
        entity.id = document.id();
        entity.knowledgeBaseId = document.knowledgeBaseId();
        entity.fileId = document.fileId();
        entity.status = document.status();
        entity.indexStatus = document.indexStatus().value();
        entity.activeRevisionId = document.activeRevisionId();
        entity.lastFailureCode = document.lastFailureCode();
        entity.lastFailureMessage = document.lastFailureMessage();
        entity.createAt = document.createAt();
        entity.updateAt = document.updateAt();

        try {
            if (mapper.insert(entity) != 1) {
                throw new IllegalStateException("保存知识文档结果不符合预期");
            }
        } catch (DuplicateKeyException exception) {
            throw new KnowledgeConflictException(
                    "DOCUMENT_ALREADY_REGISTERED", "该文件已登记到此知识库", exception);
        }
        return toDomain(entity);
    }

    /**
     * 根据文档 ID 查询知识文档。
     *
     * @param id 文档唯一标识
     * @return 包含领域实体的 Optional，若不存在则为空
     */
    @Override
    public Optional<KnowledgeDocument> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id))
                .map(MyBatisKnowledgeDocumentRepository::toDomain);
    }

    /**
     * 根据知识库 ID 和文件 ID 查询登记记录。
     *
     * @param knowledgeBaseId 知识库唯一标识
     * @param fileId 文件唯一标识
     * @return 包含领域实体的 Optional，若不存在则为空
     */
    @Override
    public Optional<KnowledgeDocument> findByKnowledgeBaseAndFile(
            String knowledgeBaseId, String fileId) {
        var query =
                new QueryWrapper<KnowledgeDocumentEntity>()
                        .eq("knowledge_base_id", knowledgeBaseId)
                        .eq("file_id", fileId);
        return Optional.ofNullable(mapper.selectOne(query))
                .map(MyBatisKnowledgeDocumentRepository::toDomain);
    }

    /**
     * 查询指定知识库下的所有文档，按创建时间和 ID 升序排序。
     *
     * @param knowledgeBaseId 知识库唯一标识
     * @return 文档领域实体列表
     */
    @Override
    public List<KnowledgeDocument> findByKnowledgeBaseId(String knowledgeBaseId) {
        return mapper
                .selectList(
                        new QueryWrapper<KnowledgeDocumentEntity>()
                                .eq("knowledge_base_id", knowledgeBaseId)
                                .orderByAsc("create_at", "id"))
                .stream()
                .map(MyBatisKnowledgeDocumentRepository::toDomain)
                .toList();
    }

    /** 将实体对象转换为领域对象。 */
    private static KnowledgeDocument toDomain(KnowledgeDocumentEntity entity) {
        return new KnowledgeDocument(
                entity.id,
                entity.knowledgeBaseId,
                entity.fileId,
                entity.status,
                DocumentIndexStatus.fromValue(entity.indexStatus),
                entity.activeRevisionId,
                entity.lastFailureCode,
                entity.lastFailureMessage,
                entity.createAt,
                entity.updateAt);
    }
}
