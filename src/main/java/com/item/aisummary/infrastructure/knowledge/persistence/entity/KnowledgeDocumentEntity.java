package com.item.aisummary.infrastructure.knowledge.persistence.entity;

import com.item.aisummary.infrastructure.persistence.typehandler.UtcInstantTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;

@TableName(value = "knowledge_document", autoResultMap = true)
public final class KnowledgeDocumentEntity {
    @TableId(type = IdType.INPUT) public String id;
    public String knowledgeBaseId;
    public String fileId;
    public int status;
    public String indexStatus;
    public String activeRevisionId;
    public String lastFailureCode;
    public String lastFailureMessage;
    @TableField(value = "create_at", typeHandler = UtcInstantTypeHandler.class) public Instant createAt;
    @TableField(value = "update_at", typeHandler = UtcInstantTypeHandler.class) public Instant updateAt;
}
