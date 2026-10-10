package com.item.aisummary.domain.knowledge.exception;

/** 内部关联或详情查询未找到；尚未定义知识库 HTTP 错误映射。 */
public final class KnowledgeNotFoundException extends RuntimeException {
    public KnowledgeNotFoundException(String resource) { super(resource + "不存在"); }
}
