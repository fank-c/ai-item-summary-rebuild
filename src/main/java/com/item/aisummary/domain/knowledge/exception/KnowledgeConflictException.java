package com.item.aisummary.domain.knowledge.exception;

/** 可由内部调用方识别的业务冲突，数据库原因保留但不作为公开消息。 */
public final class KnowledgeConflictException extends RuntimeException {
    private final String code;

    /**
     * 构造知识库业务冲突异常。
     *
     * @param code 供内部调用方识别的稳定冲突码
     * @param message 冲突原因描述
     */
    public KnowledgeConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 构造携带底层原因的知识库业务冲突异常。
     *
     * @param code 供内部调用方识别的稳定冲突码
     * @param message 冲突原因描述
     * @param cause 底层异常原因（如数据库唯一索引冲突）
     */
    public KnowledgeConflictException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** 返回供内部调用方分支处理的稳定冲突码。 */
    public String code() {
        return code;
    }
}
