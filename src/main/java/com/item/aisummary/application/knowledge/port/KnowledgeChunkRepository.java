package com.item.aisummary.application.knowledge.port;

import com.item.aisummary.domain.knowledge.model.KnowledgeChunk;
import java.util.List;
import java.util.Optional;

/**
 * 知识片段（KnowledgeChunk）持久化能力端口。
 *
 * <p>负责知识片段在 MySQL 中的单条插入、按 ID 查询、按版本升序列表查询及实际片段总数统计。
 */
public interface KnowledgeChunkRepository {
    /**
     * 插入单个知识片段记录。
     *
     * <p>批次保存的事务原子性由上层应用服务负责。
     *
     * @param chunk 待保存的知识片段对象
     * @return 保存成功后的知识片段对象
     */
    KnowledgeChunk insert(KnowledgeChunk chunk);

    /**
     * 根据 ID 查询知识片段。
     *
     * @param id 片段唯一标识
     * @return 包含片段的 Optional，不存在时为空
     */
    Optional<KnowledgeChunk> findById(String id);

    /**
     * 查询指定版本下的所有片段列表，固定按 sequence_no 升序排序。
     *
     * @param revisionId 版本唯一标识
     * @return 片段列表
     */
    List<KnowledgeChunk> findByRevisionId(String revisionId);

    /**
     * 统计指定版本内的片段总数。
     *
     * @param revisionId 版本唯一标识
     * @return 该版本下的片段数量
     */
    long countByRevisionId(String revisionId);
}
