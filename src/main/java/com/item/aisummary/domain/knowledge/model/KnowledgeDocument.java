package com.item.aisummary.domain.knowledge.model;

import com.item.aisummary.domain.knowledge.enums.DocumentIndexStatus;
import java.time.Instant;

/** 文件登记关系；活动版本指针仅映射，本阶段服务不修改该指针。 */
public record KnowledgeDocument(String id, String knowledgeBaseId, String fileId, int status,
                                DocumentIndexStatus indexStatus, String activeRevisionId,
                                String lastFailureCode, String lastFailureMessage,
                                Instant createAt, Instant updateAt) { }
