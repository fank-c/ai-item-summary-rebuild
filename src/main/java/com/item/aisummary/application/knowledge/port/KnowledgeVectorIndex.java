package com.item.aisummary.application.knowledge.port;

import com.item.aisummary.application.knowledge.command.KnowledgeVectorEntry;
import java.util.List;

/**
 * 外部向量索引能力端口（Port）。
 *
 * <p>封装 Embedding 模型向量计算及向向量数据库（如 Qdrant）的写入和版本过滤删除。
 * 具体供应商实现、HTTP/gRPC 通信和异常转换封装在基础设施层中。
 *
 * <p>业务与事务边界：
 * <ul>
 *   <li>MySQL 数据库与向量存储之间没有分布式事务；向量写入在 MySQL 短事务之外执行。</li>
 *   <li>只有已持久化且属于 {@code RETRIEVAL} 角色的片段才能交给此端口写入；{@code CONTEXT} 片段不写入向量库。</li>
 *   <li>适配器使用同片段相同的 32 位 UUID（转换为带连字符的标准 UUID），不得在向量库端生成独立的主键映射。</li>
 *   <li>写入方法为批次操作，正常返回表示整批调用成功；部分失败也必须抛出异常，由编排服务统一清理该版本并记录失败状态。</li>
 *   <li>删除操作必须严格按 {@code revision_id} 等值过滤执行，严禁清空整个集合。</li>
 * </ul>
 */
public interface KnowledgeVectorIndex {
    /**
     * 将一批已保存的检索片段条目写入向量数据库。
     *
     * @param entries 待写入的向量条目列表（非空，全部为 RETRIEVAL 角色）
     * @throws IllegalStateException 当 Embedding 调用或向量库写入失败时抛出
     */
    void add(List<KnowledgeVectorEntry> entries);

    /**
     * 按版本 ID 物理清理该版本关联的所有向量记录。
     *
     * @param revisionId 目标版本唯一标识
     * @throws IllegalStateException 当向量库删除操作失败时抛出
     */
    void deleteByRevisionId(String revisionId);
}
