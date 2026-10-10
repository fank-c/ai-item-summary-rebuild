package com.item.aisummary.infrastructure.knowledge.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.domain.knowledge.enums.RevisionStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocumentRevision;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeRevisionEntity;
import com.item.aisummary.infrastructure.knowledge.persistence.mapper.KnowledgeRevisionMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 文档版本表的 MyBatis 持久化适配器。
 *
 * <p>片段批次需要锁住版本行并确认实际隔离级别，这里的检查与 SQL 锁配合完成事务合同。
 */
@Repository
public class MyBatisKnowledgeRevisionRepository implements KnowledgeRevisionRepository {
    private final KnowledgeRevisionMapper mapper;
    private final DataSource dataSource;

    public MyBatisKnowledgeRevisionRepository(
            KnowledgeRevisionMapper mapper, DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    /**
     * 插入新的文档版本。
     *
     * <p>若文档版本号出现唯一键竞争，抛出 REVISION_NUMBER_CONFLICT 业务异常。
     *
     * @param revision 文档版本领域实体
     * @return 插入成功后的版本领域实体
     * @throws KnowledgeConflictException 版本号冲突
     * @throws IllegalStateException 受影响行数不为 1
     */
    @Override
    public KnowledgeDocumentRevision insert(KnowledgeDocumentRevision revision) {
        KnowledgeRevisionEntity entity = new KnowledgeRevisionEntity();
        entity.id = revision.id();
        entity.knowledgeDocumentId = revision.knowledgeDocumentId();
        entity.revisionNumber = revision.revisionNumber();
        entity.revisionStatus = revision.revisionStatus().value();
        entity.sourceSha256 = revision.sourceSha256();
        entity.chunkCount = revision.chunkCount();
        entity.failureStage = revision.failureStage();
        entity.failureCode = revision.failureCode();
        entity.failureMessage = revision.failureMessage();
        entity.createdAt = revision.createdAt();
        entity.startedAt = revision.startedAt();
        entity.completedAt = revision.completedAt();
        entity.publishedAt = revision.publishedAt();
        entity.cleanedAt = revision.cleanedAt();

        try {
            if (mapper.insert(entity) != 1) {
                throw new IllegalStateException("保存文档版本结果不符合预期");
            }
        } catch (DuplicateKeyException exception) {
            throw new KnowledgeConflictException(
                    "REVISION_NUMBER_CONFLICT", "文档版本号冲突，本次创建失败", exception);
        }
        return toDomain(entity);
    }

    /**
     * 根据版本 ID 查询版本。
     *
     * @param id 版本唯一标识
     * @return 包含领域实体的 Optional，若不存在则为空
     */
    @Override
    public Optional<KnowledgeDocumentRevision> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id))
                .map(MyBatisKnowledgeRevisionRepository::toDomain);
    }

    /**
     * 查询指定文档的所有版本，按版本号升序排序。
     *
     * @param knowledgeDocumentId 文档唯一标识
     * @return 版本领域实体列表
     */
    @Override
    public List<KnowledgeDocumentRevision> findByDocumentId(String knowledgeDocumentId) {
        return mapper
                .selectList(
                        new QueryWrapper<KnowledgeRevisionEntity>()
                                .eq("knowledge_document_id", knowledgeDocumentId)
                                .orderByAsc("revision_number"))
                .stream()
                .map(MyBatisKnowledgeRevisionRepository::toDomain)
                .toList();
    }

    /**
     * 查询文档当前最大的版本号。
     *
     * @param knowledgeDocumentId 文档唯一标识
     * @return 最大版本号（无版本时返回 0）
     */
    @Override
    public int findMaxRevisionNumber(String knowledgeDocumentId) {
        return mapper.findMaxRevisionNumber(knowledgeDocumentId);
    }

    /**
     * 使用排他行锁查询版本记录，必须在 READ_COMMITTED 事务中执行。
     *
     * @param id 版本唯一标识
     * @return 包含锁定后版本实体的 Optional，若不存在则为空
     */
    @Override
    public Optional<KnowledgeDocumentRevision> findByIdForUpdate(String id) {
        // REQUIRED 加入外层事务时不会替换隔离级别；先检查实际连接，避免旧快照使总数失准。
        requireReadCommittedTransaction();
        String locked = mapper.lockById(id);
        return locked == null ? Optional.empty() : findById(locked);
    }

    /**
     * 仅更新处于 building 状态的版本的片段数量。
     *
     * @param id 版本唯一标识
     * @param chunkCount 更新后的片段计数
     * @return 数据库受影响行数
     */
    @Override
    public int updateChunkCount(String id, int chunkCount) {
        return mapper.update(
                null,
                new UpdateWrapper<KnowledgeRevisionEntity>()
                        .eq("id", id)
                        .eq("revision_status", RevisionStatus.BUILDING.value())
                        .set("chunk_count", chunkCount));
    }

    /** 校验当前线程处于活动事务中，并且底层连接确实采用 READ_COMMITTED 隔离级别。 */
    private void requireReadCommittedTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("版本锁必须在事务中使用");
        }

        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                throw new IllegalStateException("片段保存事务必须使用 READ_COMMITTED 隔离级别");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取片段事务隔离级别失败", exception);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /** 将实体对象转换为领域对象。 */
    private static KnowledgeDocumentRevision toDomain(KnowledgeRevisionEntity entity) {
        return new KnowledgeDocumentRevision(
                entity.id,
                entity.knowledgeDocumentId,
                entity.revisionNumber,
                RevisionStatus.fromValue(entity.revisionStatus),
                entity.sourceSha256,
                entity.chunkCount,
                entity.failureStage,
                entity.failureCode,
                entity.failureMessage,
                entity.createdAt,
                entity.startedAt,
                entity.completedAt,
                entity.publishedAt,
                entity.cleanedAt);
    }
}
