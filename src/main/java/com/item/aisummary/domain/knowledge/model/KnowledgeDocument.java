package com.item.aisummary.domain.knowledge.model;

import com.item.aisummary.domain.knowledge.enums.DocumentIndexStatus;
import java.time.Instant;

/** 文件登记关系；活动版本指针仅映射，本阶段服务不修改该指针。 */
public record KnowledgeDocument(
        /** 知识文档业务 ID。 */
        String id,
        /** 所属知识库 ID；本阶段通过逻辑关联维护。 */
        String knowledgeBaseId,
        /** 源文件 ID。 */
        String fileId,
        /** 文档业务状态。 */
        int status,
        /** 文档索引生命周期状态。 */
        DocumentIndexStatus indexStatus,
        /** 当前可查询的完整版本 ID；失败重建时可保留旧值。 */
        String activeRevisionId,
        /** 最近一次构建失败编码。 */
        String lastFailureCode,
        /** 最近一次构建失败的脱敏摘要。 */
        String lastFailureMessage,
        /** 创建时间，按 UTC 微秒精度保存。 */
        Instant createAt,
        /** 最后更新时间，按 UTC 微秒精度保存。 */
        Instant updateAt) {}
