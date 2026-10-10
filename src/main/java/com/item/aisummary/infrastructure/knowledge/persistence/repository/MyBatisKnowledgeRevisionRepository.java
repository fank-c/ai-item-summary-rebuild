package com.item.aisummary.infrastructure.knowledge.persistence.repository;

import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.domain.knowledge.enums.RevisionStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocumentRevision;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeRevisionEntity;
import com.item.aisummary.infrastructure.knowledge.persistence.mapper.KnowledgeRevisionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisKnowledgeRevisionRepository implements KnowledgeRevisionRepository {
    private final KnowledgeRevisionMapper mapper;
    private final DataSource dataSource;
    public MyBatisKnowledgeRevisionRepository(KnowledgeRevisionMapper mapper, DataSource dataSource) {
        this.mapper = mapper; this.dataSource = dataSource;
    }

    @Override public KnowledgeDocumentRevision insert(KnowledgeDocumentRevision revision) {
        var entity = new KnowledgeRevisionEntity();
        entity.id = revision.id(); entity.knowledgeDocumentId = revision.knowledgeDocumentId();
        entity.revisionNumber = revision.revisionNumber(); entity.revisionStatus = revision.revisionStatus().value();
        entity.sourceSha256 = revision.sourceSha256(); entity.chunkCount = revision.chunkCount();
        entity.failureStage = revision.failureStage(); entity.failureCode = revision.failureCode(); entity.failureMessage = revision.failureMessage();
        entity.createdAt = revision.createdAt(); entity.startedAt = revision.startedAt(); entity.completedAt = revision.completedAt();
        entity.publishedAt = revision.publishedAt(); entity.cleanedAt = revision.cleanedAt();
        try {
            if (mapper.insert(entity) != 1) throw new IllegalStateException("保存文档版本结果不符合预期");
        } catch (DuplicateKeyException exception) {
            throw new KnowledgeConflictException("REVISION_NUMBER_CONFLICT", "文档版本号冲突，本次创建失败", exception);
        }
        return toDomain(entity);
    }
    @Override public Optional<KnowledgeDocumentRevision> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MyBatisKnowledgeRevisionRepository::toDomain);
    }
    @Override public List<KnowledgeDocumentRevision> findByDocumentId(String knowledgeDocumentId) {
        return mapper.selectList(new QueryWrapper<KnowledgeRevisionEntity>().eq("knowledge_document_id", knowledgeDocumentId)
            .orderByAsc("revision_number")).stream().map(MyBatisKnowledgeRevisionRepository::toDomain).toList();
    }
    @Override public int findMaxRevisionNumber(String knowledgeDocumentId) {
        return mapper.findMaxRevisionNumber(knowledgeDocumentId);
    }
    @Override public Optional<KnowledgeDocumentRevision> findByIdForUpdate(String id) {
        // REQUIRED 加入外层事务时不会替换隔离级别；检查实际连接，防止旧快照使总数失准。
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
        String locked = mapper.lockById(id);
        return locked == null ? Optional.empty() : findById(locked);
    }
    @Override public int updateChunkCount(String id, int chunkCount) {
        return mapper.update(null, new UpdateWrapper<KnowledgeRevisionEntity>().eq("id", id)
            .eq("revision_status", RevisionStatus.BUILDING.value()).set("chunk_count", chunkCount));
    }
    private static KnowledgeDocumentRevision toDomain(KnowledgeRevisionEntity entity) {
        return new KnowledgeDocumentRevision(entity.id, entity.knowledgeDocumentId, entity.revisionNumber,
            RevisionStatus.fromValue(entity.revisionStatus), entity.sourceSha256, entity.chunkCount,
            entity.failureStage, entity.failureCode, entity.failureMessage, entity.createdAt, entity.startedAt,
            entity.completedAt, entity.publishedAt, entity.cleanedAt);
    }
}
