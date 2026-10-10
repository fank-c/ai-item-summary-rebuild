package com.item.aisummary.domain.knowledge.model;

import java.time.Instant;

/** 知识库自身信息；不保存所有者或权限关系，名称允许重复。 */
public record KnowledgeBase(String id, String name, int status, Instant createAt, Instant updateAt) { }
