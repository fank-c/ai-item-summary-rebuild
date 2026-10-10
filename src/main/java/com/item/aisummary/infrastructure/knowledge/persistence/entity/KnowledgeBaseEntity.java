package com.item.aisummary.infrastructure.knowledge.persistence.entity;

import com.item.aisummary.infrastructure.persistence.typehandler.UtcInstantTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;

@TableName(value = "knowledge_base", autoResultMap = true)
public final class KnowledgeBaseEntity {
    @TableId(type = IdType.INPUT) public String id;
    public String name;
    public int status;
    @TableField(value = "create_at", typeHandler = UtcInstantTypeHandler.class) public Instant createAt;
    @TableField(value = "update_at", typeHandler = UtcInstantTypeHandler.class) public Instant updateAt;
}
