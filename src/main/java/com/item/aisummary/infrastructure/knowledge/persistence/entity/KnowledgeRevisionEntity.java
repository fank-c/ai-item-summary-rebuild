package com.item.aisummary.infrastructure.knowledge.persistence.entity;

import com.item.aisummary.infrastructure.persistence.typehandler.UtcInstantTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;

/** 此表使用 created_at，不能套用其他三表的 create_at。 */
@TableName(value = "knowledge_document_revision", autoResultMap = true)
public final class KnowledgeRevisionEntity {
    @TableId(type = IdType.INPUT) public String id;
    public String knowledgeDocumentId;
    public int revisionNumber;
    public String revisionStatus;
    public String sourceSha256;
    public int chunkCount;
    public String failureStage;
    public String failureCode;
    public String failureMessage;
    @TableField(value = "created_at", typeHandler = UtcInstantTypeHandler.class) public Instant createdAt;
    @TableField(value = "started_at", typeHandler = UtcInstantTypeHandler.class) public Instant startedAt;
    @TableField(value = "completed_at", typeHandler = UtcInstantTypeHandler.class) public Instant completedAt;
    @TableField(value = "published_at", typeHandler = UtcInstantTypeHandler.class) public Instant publishedAt;
    @TableField(value = "cleaned_at", typeHandler = UtcInstantTypeHandler.class) public Instant cleanedAt;
}
