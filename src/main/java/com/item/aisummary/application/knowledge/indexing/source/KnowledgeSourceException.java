package com.item.aisummary.application.knowledge.indexing.source;

/**
 * 源文件原始字节准备与校验异常。
 *
 * <p>当 {@link KnowledgeSourceReader} 在读取
 * 对象存储响应流、校验文件大小预算或比对 SHA-256 摘要时遇到不符合预期的状态时抛出。
 *
 * <p>对外摘要只使用安全、固定的脱敏文本；任何底层 I/O 或对象存储 SDK 异常均保留在 cause 中供内部调试，
 * 禁止在外部错误消息中回显底层细节。
 */
public class KnowledgeSourceException extends RuntimeException {
    private final String code;

    /**
     * 构造源文件准备异常。
     *
     * @param code 安全错误码（例如 "SOURCE_SIZE_EXCEEDED"、"SOURCE_DIGEST_MISMATCH"）
     * @param message 脱敏的展示错误消息
     */
    public KnowledgeSourceException(String code, String message) {
        this(code, message, null);
    }

    /**
     * 构造包含底层原因的源文件准备异常。
     *
     * @param code 安全错误码
     * @param message 脱敏的展示错误消息
     * @param cause 底层原始异常
     */
    public KnowledgeSourceException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * 获取源文件准备失败的错误码。
     *
     * @return 错误码字符串
     */
    public String code() { return code; }
}
