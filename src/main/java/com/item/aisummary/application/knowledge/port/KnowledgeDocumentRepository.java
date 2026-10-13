package com.item.aisummary.application.knowledge.port;

import com.item.aisummary.domain.knowledge.enums.DocumentIndexStatus;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocument;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 知识文档持久化能力。 */
public interface KnowledgeDocumentRepository {
    /**
     * 插入知识库文档记录。
     *
     * <p>若同一知识库已存在相同源文件，抛出 DOCUMENT_ALREADY_REGISTERED 业务冲突。
     *
     * @param document 待保存的知识文档对象
     * @return 保存成功后的知识文档对象
     */
    KnowledgeDocument insert(KnowledgeDocument document);

    /**
     * 根据 ID 查询知识文档。
     *
     * @param id 文档唯一标识
     * @return 包含文档的 Optional，不存在时为空
     */
    Optional<KnowledgeDocument> findById(String id);

    /**
     * 在数据库事务内使用排他锁（FOR UPDATE）查询指定知识文档。
     *
     * <p>必须处于 READ_COMMITTED 事务，基础设施核对实际连接隔离级别；
     * 同文档的构建创建、失败记录和版本发布必须先取得此锁，以避免并发修改和旧快照。
     *
     * @param id 文档唯一标识（32 位 UUID）
     * @return 包含已加锁文档实体的 Optional，不存在时为空
     */
    Optional<KnowledgeDocument> findByIdForUpdate(String id);

    /**
     * 更新知识文档的活动版本指针与索引状态（发布成功或构建失败）。
     *
     * @param id 文档 ID
     * @param activeRevisionId 新的活动版本 ID
     * @param status 更新后的索引状态
     * @param failureCode 业务失败码（成功发布时为 null）
     * @param failureMessage 失败原因摘要（成功发布时为 null）
     * @param updateAt 更新时刻微秒时间戳
     * @return 影响行数（预期为 1）
     */
    int updateIndexState(
            String id,
            String activeRevisionId,
            DocumentIndexStatus status,
            String failureCode,
            String failureMessage,
            Instant updateAt);

    /**
     * 根据知识库 ID 和源文件 ID 查询登记记录。
     *
     * @param knowledgeBaseId 知识库唯一标识
     * @param fileId 源文件唯一标识
     * @return 包含文档的 Optional，未登记时为空
     */
    Optional<KnowledgeDocument> findByKnowledgeBaseAndFile(String knowledgeBaseId, String fileId);

    /**
     * 查询指定知识库下的所有文档列表。
     *
     * @param knowledgeBaseId 知识库唯一标识
     * @return 文档列表；无记录时返回空列表
     */
    List<KnowledgeDocument> findByKnowledgeBaseId(String knowledgeBaseId);
}
