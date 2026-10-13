package com.item.aisummary.application.knowledge.exception;

/**
 * 知识库构建过程中的业务异常。
 *
 * <p>封装构建编排中各阶段（读取、解析、切分、持久化、向量化、发布）发生的错误。
 * 错误摘要固定使用安全、脱敏的预置文本，严禁将外部模型供应商、向量库或底层 SDK 的异常细节暴露到上层或日志。
 *
 * <p>{@code cleanupCompleted} 字段用于反映失败后派生数据（如当前版本的向量）是否在向量数据库中成功清理完成，
 * 使得调用方可以区分“构建失败且派生数据已清理”与“构建失败但外部清理未完成/待人工核对”两种不同的物理状态。
 */
public class KnowledgeBuildException extends RuntimeException {
    private final KnowledgeBuildStage stage;
    private final String code;
    private final boolean cleanupCompleted;

    /**
     * 构造知识库构建异常。
     *
     * @param stage 失败发生的构建阶段标识
     * @param code 内部安全错误码（例如 "SOURCE_READ_FAILED"、"VECTOR_WRITE_FAILED"）
     * @param message 固定脱敏的展示错误摘要
     * @param cleanupCompleted 当前版本在向量库中的派生数据是否已成功清理完成
     * @param cause 导致构建失败的底层原始异常
     */
    public KnowledgeBuildException(KnowledgeBuildStage stage, String code, String message,
            boolean cleanupCompleted, Throwable cause) {
        super(message, cause);
        this.stage = stage;
        this.code = code;
        this.cleanupCompleted = cleanupCompleted;
    }

    /** 获取失败发生的构建阶段。 */
    public KnowledgeBuildStage stage() { return stage; }

    /** 获取内部安全错误码。 */
    public String code() { return code; }

    /** 获取向量库派生数据是否清理成功标志。 */
    public boolean cleanupCompleted() { return cleanupCompleted; }
}
