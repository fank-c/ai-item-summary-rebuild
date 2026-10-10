package com.item.aisummary.domain.knowledge.model;

import com.item.aisummary.domain.knowledge.enums.RevisionStatus;
import java.time.Instant;

/** 文档版本结果与生命周期字段；无实际结果时摘要和完成时间均为空。 */
public record KnowledgeDocumentRevision(String id, String knowledgeDocumentId, int revisionNumber,
                                        RevisionStatus revisionStatus, String sourceSha256, int chunkCount,
                                        String failureStage, String failureCode, String failureMessage,
                                        Instant createdAt, Instant startedAt, Instant completedAt,
                                        Instant publishedAt, Instant cleanedAt) { }
