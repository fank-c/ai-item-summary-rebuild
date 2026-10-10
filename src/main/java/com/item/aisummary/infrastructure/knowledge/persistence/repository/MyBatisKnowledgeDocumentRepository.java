package com.item.aisummary.infrastructure.knowledge.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.domain.knowledge.enums.DocumentIndexStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocument;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeDocumentEntity;
import com.item.aisummary.infrastructure.knowledge.persistence.mapper.KnowledgeDocumentMapper;
import com.item.aisummary.infrastructure.persistence.typehandler.UtcInstantTypeHandler;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 知识文档表的 MyBatis 持久化适配器。
 *
 * <p>处理唯一键冲突与事务排他行锁查询。
 */
@Repository
public class MyBatisKnowledgeDocumentRepository implements KnowledgeDocumentRepository {
    private final KnowledgeDocumentMapper mapper;

    public MyBatisKnowledgeDocumentRepository(KnowledgeDocumentMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 插入文档记录，同库重复登记抛出 DOCUMENT_ALREADY_REGISTERED 异常。
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
     * 根据文档 ID 查询知识文档详情。
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
     * @return 包含领域实体的 Optional，未登记时为空
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
     * 查询指定知识库下的所有文档列表，按创建时间与 ID 升序排序。
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

    /**
     * 在事务中使用排他锁（SELECT ... FOR UPDATE）锁定文档行。
     *
     * <p>同文档的构建开启、发布或失败更新必须在此锁保护下进行，以避免并发修改。
     *
     * @param id 文档唯一标识
     * @return 包含已加锁实体的 Optional，不存在时为空
     * @throws IllegalStateException 当当前线程未处于活动事务中时抛出
     */
    @Override
    public Optional<KnowledgeDocument> findByIdForUpdate(String id) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("文档锁必须在事务中使用");
        }
        String locked = mapper.lockById(id);
        return locked == null ? Optional.empty() : findById(locked);
    }

    /**
     * 更新文档的活动版本指针、索引生命周期状态及错误摘要。
     *
     * @param id 文档唯一标识
     * @param activeRevisionId 新的活动版本 ID
     * @param status 更新后的索引状态枚举
     * @param failureCode 错误码（成功发布时为 null）
     * @param failureMessage 错误消息摘要（成功发布时为 null）
     * @param updateAt 更新时刻微秒时间戳
     * @return 数据库受影响行数
     */
    @Override
    public int updateIndexState(
            String id,
            String activeRevisionId,
            DocumentIndexStatus status,
            String failureCode,
            String failureMessage,
            Instant updateAt) {
        return mapper.update(
                null,
                new UpdateWrapper<KnowledgeDocumentEntity>()
                        .eq("id", id)
                        .set("active_revision_id", activeRevisionId)
                        .set("index_status", status.value())
                        .set("last_failure_code", failureCode)
                        .set("last_failure_message", failureMessage)
                        .set(
                                "update_at",
                                updateAt,
                                "typeHandler=" + UtcInstantTypeHandler.class.getName()));
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
