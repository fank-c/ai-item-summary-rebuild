package com.item.aisummary.domain.knowledge.model;

import java.time.Instant;

/** 知识库自身信息；不保存所有者或权限关系，名称允许重复。 */
public record KnowledgeBase(
        /** 知识库业务 ID。 */
        String id,
        /** 知识库名称；允许多个知识库同名。 */
        String name,
        /** 知识库状态；当前 1 表示可用。 */
        int status,
        /** 创建时间，按 UTC 微秒精度保存。 */
        Instant createAt,
        /** 最后更新时间，按 UTC 微秒精度保存。 */
        Instant updateAt) {}
