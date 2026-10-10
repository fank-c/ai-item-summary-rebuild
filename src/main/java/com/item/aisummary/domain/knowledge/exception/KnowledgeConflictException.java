package com.item.aisummary.domain.knowledge.exception;

/** 可由内部调用方识别的业务冲突，数据库原因保留但不作为公开消息。 */
public final class KnowledgeConflictException extends RuntimeException {
    private final String code;
    public KnowledgeConflictException(String code, String message) { super(message); this.code = code; }
    public KnowledgeConflictException(String code, String message, Throwable cause) {
        super(message, cause); this.code = code;
    }
    public String code() { return code; }
}
