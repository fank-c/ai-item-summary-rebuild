package com.item.aisummary.application.knowledge.indexing.build;

/** 构建能力未启用或所需策略未注册；在创建版本前拒绝请求，不表示构建执行失败。 */
public final class KnowledgeBuildUnavailableException extends IllegalStateException {
    /** 用于区分 RAG 关闭、解析策略缺失和切分策略缺失的稳定错误码。 */
    private final String code;

    /** 使用固定的能力不可用说明构造异常，不传入供应商异常或凭据。 */
    public KnowledgeBuildUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 返回调用方可识别的能力不可用编码。 */
    public String code() {
        return code;
    }
}
