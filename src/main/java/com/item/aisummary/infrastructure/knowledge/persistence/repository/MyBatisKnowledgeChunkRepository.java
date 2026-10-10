package com.item.aisummary.infrastructure.knowledge.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.item.aisummary.application.knowledge.port.KnowledgeChunkRepository;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.model.KnowledgeChunk;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeChunkEntity;
import com.item.aisummary.infrastructure.knowledge.persistence.mapper.KnowledgeChunkMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * 知识片段表的 MyBatis 持久化适配器。
 *
 * <p>处理片段实体持久化、序号唯一约束冲突转换与按版本聚合查询。
 */
@Repository
public class MyBatisKnowledgeChunkRepository implements KnowledgeChunkRepository {
    private final KnowledgeChunkMapper mapper;

    public MyBatisKnowledgeChunkRepository(KnowledgeChunkMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 插入单个片段记录，版本内序号冲突抛出 CHUNK_SEQUENCE_CONFLICT。
     */
    @Override
    public KnowledgeChunk insert(KnowledgeChunk chunk) {
        KnowledgeChunkEntity entity = new KnowledgeChunkEntity();
        entity.id = chunk.id();
        entity.revisionId = chunk.revisionId();
        entity.sequenceNo = chunk.sequenceNo();
        entity.content = chunk.content();
        entity.headingPath = chunk.headingPath();
        entity.tokenCount = chunk.tokenCount();
        entity.startOffset = chunk.startOffset();
        entity.endOffset = chunk.endOffset();
        entity.pageStart = chunk.pageStart();
        entity.pageEnd = chunk.pageEnd();
        entity.createAt = chunk.createAt();

        try {
            if (mapper.insert(entity) != 1) {
                throw new IllegalStateException("保存知识片段结果不符合预期");
            }
        } catch (DuplicateKeyException exception) {
            throw new KnowledgeConflictException(
                    "CHUNK_SEQUENCE_CONFLICT", "版本内片段序号冲突，本批保存失败", exception);
        }
        return toDomain(entity);
    }

    /**
     * 根据 ID 查询片段。
     *
     * @param id 片段唯一标识
     * @return 包含领域实体的 Optional，若不存在则为空
     */
    @Override
    public Optional<KnowledgeChunk> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id))
                .map(MyBatisKnowledgeChunkRepository::toDomain);
    }

    /**
     * 查询指定版本下的所有片段，固定按 sequence_no 升序排序。
     *
     * @param revisionId 版本唯一标识
     * @return 片段领域实体列表
     */
    @Override
    public List<KnowledgeChunk> findByRevisionId(String revisionId) {
        return mapper
                .selectList(
                        new QueryWrapper<KnowledgeChunkEntity>()
                                .eq("revision_id", revisionId)
                                .orderByAsc("sequence_no"))
                .stream()
                .map(MyBatisKnowledgeChunkRepository::toDomain)
                .toList();
    }

    /**
     * 统计指定版本内的片段总数。
     *
     * @param revisionId 版本唯一标识
     * @return 该版本下的片段数量
     */
    @Override
    public long countByRevisionId(String revisionId) {
        return mapper.selectCount(
                new QueryWrapper<KnowledgeChunkEntity>().eq("revision_id", revisionId));
    }

    /** 将实体对象转换为领域对象。 */
    private static KnowledgeChunk toDomain(KnowledgeChunkEntity entity) {
        return new KnowledgeChunk(
                entity.id,
                entity.revisionId,
                entity.sequenceNo,
                entity.content,
                entity.headingPath,
                entity.tokenCount,
                entity.startOffset,
                entity.endOffset,
                entity.pageStart,
                entity.pageEnd,
                entity.createAt);
    }
}
