package com.item.aisummary.application.knowledge.port;

import com.item.aisummary.domain.knowledge.model.KnowledgeBase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 知识库持久化能力。 */
public interface KnowledgeBaseRepository {
    /**
     * 插入新的知识库元数据。
     *
     * @param base 待保存的知识库领域对象
     * @return 保存成功后的知识库对象
     */
    KnowledgeBase insert(KnowledgeBase base);

    /**
     * 根据 ID 查询知识库。
     *
     * @param id 知识库唯一标识
     * @return 包含知识库的 Optional，不存在时为空
     */
    Optional<KnowledgeBase> findById(String id);

    /**
     * 查询系统中全部知识库列表。
     *
     * @return 知识库列表
     */
    List<KnowledgeBase> findAll();

    /**
     * 重命名指定知识库，并更新更新时间。
     *
     * @param id 知识库唯一标识
     * @param name 新的知识库名称
     * @param updateAt 更新时间戳
     * @return 数据库受影响行数
     */
    int rename(String id, String name, Instant updateAt);
}
