package com.item.aisummary.infrastructure.knowledge.persistence.entity;

import com.item.aisummary.domain.knowledge.value.Heading;
import com.item.aisummary.infrastructure.knowledge.persistence.typehandler.HeadingPathTypeHandler;
import com.item.aisummary.infrastructure.persistence.typehandler.UtcInstantTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;
import java.util.List;

@TableName(value = "knowledge_chunk", autoResultMap = true)
public final class KnowledgeChunkEntity {
    @TableId(type = IdType.INPUT) public String id;
    public String revisionId;
    public int sequenceNo;
    public String content;
    @TableField(value = "heading_path", typeHandler = HeadingPathTypeHandler.class) public List<Heading> headingPath;
    public Integer tokenCount;
    public Integer startOffset;
    public Integer endOffset;
    public Integer pageStart;
    public Integer pageEnd;
    @TableField(value = "create_at", typeHandler = UtcInstantTypeHandler.class) public Instant createAt;
}
