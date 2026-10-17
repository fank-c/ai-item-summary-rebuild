package com.item.aisummary.domain.knowledge.exception;

/** 知识库关联或详情资源不存在；由调用边界决定协议响应，不包含持久化细节。 */
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
