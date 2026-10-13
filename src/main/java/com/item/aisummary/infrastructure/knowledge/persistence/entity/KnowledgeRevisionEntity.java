package com.item.aisummary.infrastructure.knowledge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.item.aisummary.infrastructure.persistence.typehandler.UtcInstantTypeHandler;

import java.time.Instant;

/** knowledge_document_revision 表映射；该表使用 created_at，而不是 create_at。 */
@TableName(value = "knowledge_document_revision", autoResultMap = true)
public final class KnowledgeRevisionEntity {
    /** 文档版本 ID；应用层生成的 32 位业务标识。 */
    @TableId(type = IdType.INPUT)
    public String id;

    /** 所属知识文档 ID；通过逻辑关联保存，不使用物理外键。 */
    public String knowledgeDocumentId;

    /** 同一文档内递增的版本号；由版本创建服务分配。 */
    public int revisionNumber;

    /** 版本生命周期状态；保存 building/ready/failed/superseded 等字符串值。 */
    public String revisionStatus;

    /** 创建该版本时读取的源文件 SHA-256；尚未读取时为空。 */
    public String sourceSha256;

    /** 当前版本两种角色的全部片段总数；由批次事务重新统计，不表示向量数量。 */
    public int chunkCount;

    /** 失败发生的处理阶段；版本成功或未失败时为空。 */
    public String failureStage;

    /** 版本构建失败的业务编码。 */
    public String failureCode;

    /** 版本构建失败的脱敏摘要。 */
    public String failureMessage;

    /** 版本记录创建时间；通过 UTC 类型处理器映射数据库的 DATETIME(6)。 */
    @TableField(value = "created_at", typeHandler = UtcInstantTypeHandler.class)
    public Instant createdAt;

    /** 进入构建流程的时间；building 版本创建时写入。 */
    @TableField(value = "started_at", typeHandler = UtcInstantTypeHandler.class)
    public Instant startedAt;

    /** 构建完成时间；未完成或构建失败时可以为空。 */
    @TableField(value = "completed_at", typeHandler = UtcInstantTypeHandler.class)
    public Instant completedAt;

    /** 发布为文档当前版本的时间；未发布时为空。 */
    @TableField(value = "published_at", typeHandler = UtcInstantTypeHandler.class)
    public Instant publishedAt;

    /** 向量清理完成时间；本阶段保留数据库片段。 */
    @TableField(value = "cleaned_at", typeHandler = UtcInstantTypeHandler.class)
    public Instant cleanedAt;
}
