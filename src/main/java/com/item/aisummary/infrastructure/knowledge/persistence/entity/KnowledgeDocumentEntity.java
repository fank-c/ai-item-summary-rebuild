package com.item.aisummary.infrastructure.knowledge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.item.aisummary.infrastructure.persistence.typehandler.UtcInstantTypeHandler;
import java.time.Instant;

/** knowledge_document 表映射；索引状态使用领域枚举对应的持久化字符串。 */
@TableName(value = "knowledge_document", autoResultMap = true)
public final class KnowledgeDocumentEntity {
    /** 知识文档 ID；应用层生成的 32 位业务标识。 */
    @TableId(type = IdType.INPUT)
    public String id;

    /** 所属知识库 ID；通过逻辑关联保存，不使用物理外键。 */
    public String knowledgeBaseId;

    /** 源文件 ID；指向已上传且归属校验通过的 file_asset 记录。 */
    public String fileId;

    /** 文档业务状态；当前 1 表示可用。 */
    public int status;

    /** 索引生命周期状态；保存领域枚举对应的字符串值。 */
    public String indexStatus;

    /** 当前可查询的完整版本 ID；重建失败时可以继续保留旧版本。 */
    public String activeRevisionId;

    /** 最近一次构建失败的业务编码。 */
    public String lastFailureCode;

    /** 最近一次构建失败的脱敏摘要。 */
    public String lastFailureMessage;

    /** 创建时间；通过 UTC 类型处理器映射数据库的 DATETIME(6)。 */
    @TableField(value = "create_at", typeHandler = UtcInstantTypeHandler.class)
    public Instant createAt;

    /** 最后更新时间；通过 UTC 类型处理器映射数据库的 DATETIME(6)。 */
    @TableField(value = "update_at", typeHandler = UtcInstantTypeHandler.class)
    public Instant updateAt;
}
