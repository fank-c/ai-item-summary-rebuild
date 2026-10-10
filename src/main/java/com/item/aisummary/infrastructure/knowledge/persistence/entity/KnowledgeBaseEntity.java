package com.item.aisummary.infrastructure.knowledge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.item.aisummary.infrastructure.persistence.typehandler.UtcInstantTypeHandler;
import java.time.Instant;

/** knowledge_base 表映射；持久化实体不向领域层泄露 MyBatis 注解。 */
@TableName(value = "knowledge_base", autoResultMap = true)
public final class KnowledgeBaseEntity {
    /** 知识库 ID；应用层生成的 32 位业务标识。 */
    @TableId(type = IdType.INPUT)
    public String id;

    /** 知识库名称；允许不同知识库使用相同名称。 */
    public String name;

    /** 知识库状态；当前 1 表示可用。 */
    public int status;

    /** 创建时间；通过 UTC 类型处理器映射数据库的 DATETIME(6)。 */
    @TableField(value = "create_at", typeHandler = UtcInstantTypeHandler.class)
    public Instant createAt;

    /** 最后更新时间；通过 UTC 类型处理器映射数据库的 DATETIME(6)。 */
    @TableField(value = "update_at", typeHandler = UtcInstantTypeHandler.class)
    public Instant updateAt;
}
