package com.item.aisummary.application.knowledge.port;

import com.item.aisummary.domain.knowledge.model.KnowledgeDocumentRevision;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 文档版本持久化能力。 */
public interface KnowledgeRevisionRepository {
    /**
     * 插入新的文档版本。
     *
     * @param revision 待保存的文档版本对象
     * @return 保存成功后的版本对象
     */
    KnowledgeDocumentRevision insert(KnowledgeDocumentRevision revision);

    /**
     * 根据 ID 查询版本。
     *
     * @param id 版本唯一标识
     * @return 包含版本的 Optional，不存在时为空
     */
    Optional<KnowledgeDocumentRevision> findById(String id);

    /**
     * 按版本号升序返回指定文档的所有版本列表。
     *
     * @param knowledgeDocumentId 文档唯一标识
     * @return 版本列表
     */
    List<KnowledgeDocumentRevision> findByDocumentId(String knowledgeDocumentId);

    /**
     * 查询指定文档当前已存在的最大版本号；无版本时返回 0。
     *
     * @param knowledgeDocumentId 文档唯一标识
     * @return 最大版本号，若无任何版本则返回 0
     */
    int findMaxRevisionNumber(String knowledgeDocumentId);

    /**
     * 在事务中使用排他锁（FOR UPDATE）查询版本行。
     *
     * <p>必须在 READ_COMMITTED 隔离级别事务中调用，锁保持到事务结束，用于串行化同版本的片段批次追加。
     *
     * @param id 版本唯一标识
     * @return 包含版本的 Optional
     */
    Optional<KnowledgeDocumentRevision> findByIdForUpdate(String id);

    /**
     * 仅更新 building 状态版本的片段总数 chunk_count。
     *
     * @param id 版本唯一标识
     * @param chunkCount 更新后的片段计数
     * @return 数据库受影响行数
     */
    int updateChunkCount(String id, int chunkCount);

    /**
     * 将版本流转为 ready，记录片段总数、源文件摘要与发布时间戳。
     *
     * @param id 版本 ID
     * @param documentId 归属的知识文档 ID
     * @param chunkCount 片段总数
     * @param sourceSha256 源文件 SHA-256 摘要
     * @param now 微秒时间戳
     * @return 影响行数（预期为 1）
     */
    int complete(String id, String documentId, int chunkCount, String sourceSha256, Instant now);

    /**
     * 将旧活动版本状态流转为 superseded。
     *
     * @param id 原活动版本 ID
     * @param documentId 所属文档 ID
     * @return 影响行数（预期为 1）
     */
    int supersede(String id, String documentId);

    /**
     * 将版本流转为 failed，记录失败阶段、错误码与失败原因。
     *
     * @param id 版本 ID
     * @param stage 失败阶段标识
     * @param code 脱敏业务错误码
     * @param message 失败原因摘要
     * @param now 失败时刻微秒时间戳
     * @return 影响行数（预期为 1）
     */
    int fail(String id, String stage, String code, String message, Instant now);

    /**
     * 记录非活动版本的向量已在向量库完成物理清理（写入 cleaned_at）。
     *
     * @param id 版本 ID
     * @param now 清理完成微秒时间戳
     * @return 影响行数（预期为 1）
     */
    int markCleaned(String id, Instant now);

    /**
     * 记录非活动版本向量清理失败信息。
     *
     * @param id 版本 ID
     * @param stage 失败阶段标识
     * @param code 失败错误码
     * @param message 清理失败摘要
     * @return 影响行数（预期为 1）
     */
    int recordCleanupFailure(String id, String stage, String code, String message);
}
