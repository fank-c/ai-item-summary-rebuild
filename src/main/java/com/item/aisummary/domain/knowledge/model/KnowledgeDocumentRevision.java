package com.item.aisummary.domain.knowledge.model;

import com.item.aisummary.domain.knowledge.enums.RevisionStatus;
import java.time.Instant;

/** 文档版本结果与生命周期字段；无实际结果时摘要和完成时间均为空。 */
public record KnowledgeDocumentRevision(
        /** 文档版本业务 ID。 */
        String id,
        /** 所属知识文档 ID。 */
        String knowledgeDocumentId,
        /** 同一文档内递增的版本号。 */
        int revisionNumber,
        /** 版本生命周期状态。 */
        RevisionStatus revisionStatus,
        /** 构建时读取的源文件 SHA-256 摘要。 */
        String sourceSha256,
        /** 当前版本已生成的片段数量。 */
        int chunkCount,
        /** 失败发生的处理阶段。 */
        String failureStage,
        /** 构建失败编码。 */
        String failureCode,
        /** 构建失败的脱敏摘要。 */
        String failureMessage,
        /** 版本记录创建时间。 */
        Instant createdAt,
        /** 进入构建流程的时间。 */
        Instant startedAt,
        /** 构建完成时间；未完成时为空。 */
        Instant completedAt,
        /** 发布为文档当前版本的时间；未发布时为空。 */
        Instant publishedAt,
        /** 派生数据清理完成时间；未清理时为空。 */
        Instant cleanedAt) {}
