package com.item.aisummary.application.knowledge.port;

import com.item.aisummary.domain.knowledge.enums.ChunkRole;
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
     * <p>父块存在、同版本、角色匹配及批次事务原子性由应用保存服务负责。
     * 业务写入应经过该服务，不以直接仓储写入绕过关系校验。
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
     * 按版本与全局序号查询片段；父引用只能在目标版本范围内解析。
     *
     * @param revisionId 版本唯一标识
     * @param sequenceNo 版本内全局序号
     * @return 匹配的片段，不从其他版本回退查找
     */
    Optional<KnowledgeChunk> findByRevisionIdAndSequenceNo(String revisionId, int sequenceNo);

    /**
     * 统计指定版本内的所有片段，包括 CONTEXT 与 RETRIEVAL，不表示向量数量。
     *
     * @param revisionId 版本唯一标识
     * @return 该版本下的片段总行数
     */
    long countByRevisionId(String revisionId);

    /**
     * 统计指定版本中指定角色的片段实际行数。
     *
     * @param revisionId 版本唯一标识
     * @param role 片段角色
     * @return 匹配的片段数量
     */
    long countByRevisionIdAndRole(String revisionId, ChunkRole role);
}
