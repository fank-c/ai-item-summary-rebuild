package com.item.aisummary.infrastructure.knowledge.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.item.aisummary.application.knowledge.port.KnowledgeBaseRepository;
import com.item.aisummary.domain.knowledge.model.KnowledgeBase;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeBaseEntity;
import com.item.aisummary.infrastructure.knowledge.persistence.mapper.KnowledgeBaseMapper;
import com.item.aisummary.infrastructure.persistence.typehandler.UtcInstantTypeHandler;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 知识库表的 MyBatis 持久化适配器。 */
@Repository
public class MyBatisKnowledgeBaseRepository implements KnowledgeBaseRepository {
    private final KnowledgeBaseMapper mapper;

    public MyBatisKnowledgeBaseRepository(KnowledgeBaseMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 插入知识库记录并返回领域模型。
     *
     * @param base 知识库领域实体
     * @return 插入成功后的领域实体
     * @throws IllegalStateException 受影响行数不为 1
     */
    @Override
    public KnowledgeBase insert(KnowledgeBase base) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.id = base.id();
        entity.name = base.name();
        entity.status = base.status();
        entity.createAt = base.createAt();
        entity.updateAt = base.updateAt();
        if (mapper.insert(entity) != 1) {
            throw new IllegalStateException("保存知识库结果不符合预期");
        }
        return toDomain(entity);
    }

    /**
     * 根据 ID 查询单个知识库。
     *
     * @param id 知识库唯一标识
     * @return 包含领域实体的 Optional，若不存在则为空
     */
    @Override
    public Optional<KnowledgeBase> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id))
                .map(MyBatisKnowledgeBaseRepository::toDomain);
    }

    /**
     * 查询系统中全部知识库，按创建时间与 ID 升序排序。
     *
     * @return 知识库领域模型列表
     */
    @Override
    public List<KnowledgeBase> findAll() {
        return mapper
                .selectList(new QueryWrapper<KnowledgeBaseEntity>().orderByAsc("create_at", "id"))
                .stream()
                .map(MyBatisKnowledgeBaseRepository::toDomain)
                .toList();
    }

    /**
     * 重命名知识库并刷新其更新时间。
     *
     * @param id 知识库唯一标识
     * @param name 新名称
     * @param updateAt 更新时间戳
     * @return 数据库受影响行数
     */
    @Override
    public int rename(String id, String name, Instant updateAt) {
        var update =
                new UpdateWrapper<KnowledgeBaseEntity>()
                        .eq("id", id)
                        .set("name", name)
                        .set(
                                "update_at",
                                updateAt,
                                "typeHandler=" + UtcInstantTypeHandler.class.getName());
        return mapper.update(null, update);
    }

    /** 将实体对象转换为领域对象。 */
    private static KnowledgeBase toDomain(KnowledgeBaseEntity entity) {
        return new KnowledgeBase(
                entity.id, entity.name, entity.status, entity.createAt, entity.updateAt);
    }
}
