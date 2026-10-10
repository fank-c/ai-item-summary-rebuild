package com.item.aisummary.infrastructure.knowledge.persistence.repository;

import com.item.aisummary.application.knowledge.port.KnowledgeChunkRepository;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.model.KnowledgeChunk;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeChunkEntity;
import com.item.aisummary.infrastructure.knowledge.persistence.mapper.KnowledgeChunkMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisKnowledgeChunkRepository implements KnowledgeChunkRepository {
    private final KnowledgeChunkMapper mapper;
    public MyBatisKnowledgeChunkRepository(KnowledgeChunkMapper mapper) { this.mapper = mapper; }

    @Override public KnowledgeChunk insert(KnowledgeChunk chunk) {
        var entity = new KnowledgeChunkEntity();
        entity.id = chunk.id(); entity.revisionId = chunk.revisionId(); entity.sequenceNo = chunk.sequenceNo(); entity.content = chunk.content();
        entity.headingPath = chunk.headingPath(); entity.tokenCount = chunk.tokenCount();
        entity.startOffset = chunk.startOffset(); entity.endOffset = chunk.endOffset();
        entity.pageStart = chunk.pageStart(); entity.pageEnd = chunk.pageEnd(); entity.createAt = chunk.createAt();
        try {
            if (mapper.insert(entity) != 1) throw new IllegalStateException("保存知识片段结果不符合预期");
        } catch (DuplicateKeyException exception) {
            throw new KnowledgeConflictException("CHUNK_SEQUENCE_CONFLICT", "版本内片段序号冲突，本批保存失败", exception);
        }
        return toDomain(entity);
    }
    @Override public Optional<KnowledgeChunk> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MyBatisKnowledgeChunkRepository::toDomain);
    }
    @Override public List<KnowledgeChunk> findByRevisionId(String revisionId) {
        return mapper.selectList(new QueryWrapper<KnowledgeChunkEntity>().eq("revision_id", revisionId)
            .orderByAsc("sequence_no")).stream().map(MyBatisKnowledgeChunkRepository::toDomain).toList();
    }
    @Override public long countByRevisionId(String revisionId) {
        return mapper.selectCount(new QueryWrapper<KnowledgeChunkEntity>().eq("revision_id", revisionId));
    }
    private static KnowledgeChunk toDomain(KnowledgeChunkEntity entity) {
        return new KnowledgeChunk(entity.id, entity.revisionId, entity.sequenceNo, entity.content,
            entity.headingPath, entity.tokenCount, entity.startOffset, entity.endOffset,
            entity.pageStart, entity.pageEnd, entity.createAt);
    }
}
