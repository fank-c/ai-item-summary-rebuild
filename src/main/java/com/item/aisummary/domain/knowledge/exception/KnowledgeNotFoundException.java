package com.item.aisummary.domain.knowledge.exception;

/** 内部关联或详情查询未找到；尚未定义知识库 HTTP 错误映射。 */
public final class KnowledgeNotFoundException extends RuntimeException {

    /**
     * 构造知识库模块资源不存在异常。
     *
     * @param resource 资源名称（如“知识库”、“知识文档”或“文档版本”）
     */
    public KnowledgeNotFoundException(String resource) {
        super(resource + "不存在");
    }
}
