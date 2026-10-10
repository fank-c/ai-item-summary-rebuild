package com.item.aisummary.infrastructure.knowledge.persistence.repository;

import com.item.aisummary.application.knowledge.port.KnowledgeBaseRepository;
import com.item.aisummary.domain.knowledge.model.KnowledgeBase;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeBaseEntity;
import com.item.aisummary.infrastructure.knowledge.persistence.mapper.KnowledgeBaseMapper;
import com.item.aisummary.infrastructure.persistence.typehandler.UtcInstantTypeHandler;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisKnowledgeBaseRepository implements KnowledgeBaseRepository {
    private final KnowledgeBaseMapper mapper;
    public MyBatisKnowledgeBaseRepository(KnowledgeBaseMapper mapper) { this.mapper = mapper; }

    @Override public KnowledgeBase insert(KnowledgeBase base) {
        var entity = new KnowledgeBaseEntity();
        entity.id = base.id(); entity.name = base.name(); entity.status = base.status();
        entity.createAt = base.createAt(); entity.updateAt = base.updateAt();
        if (mapper.insert(entity) != 1) throw new IllegalStateException("保存知识库结果不符合预期");
        return toDomain(entity);
    }
    @Override public Optional<KnowledgeBase> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MyBatisKnowledgeBaseRepository::toDomain);
    }
    @Override public List<KnowledgeBase> findAll() {
        return mapper.selectList(new QueryWrapper<KnowledgeBaseEntity>().orderByAsc("create_at", "id"))
            .stream().map(MyBatisKnowledgeBaseRepository::toDomain).toList();
    }
    @Override public int rename(String id, String name, Instant updateAt) {
        return mapper.update(null, new UpdateWrapper<KnowledgeBaseEntity>().eq("id", id).set("name", name)
            .set("update_at", updateAt, "typeHandler=" + UtcInstantTypeHandler.class.getName()));
    }
    private static KnowledgeBase toDomain(KnowledgeBaseEntity entity) {
        return new KnowledgeBase(entity.id, entity.name, entity.status, entity.createAt, entity.updateAt);
    }
}
