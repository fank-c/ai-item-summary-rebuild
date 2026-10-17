package com.item.aisummary.web.knowledge.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingMode;

/**
 * 同步构建选项；其余切分预算沿用服务端配置，构建会读源文件并调用已启用的外部服务。
 *
 * @param mode FLAT 或 PARENT_CHILD；省略或 null 时使用 FLAT
 */
public record BuildKnowledgeDocumentRequest(ChunkingMode mode) {
    /**
     * 严格按枚举名称读取 JSON，拒绝 Jackson 默认支持的数字序号及数字字符串。
     *
     * @param mode JSON 原始值；null 表示沿用默认模式
     * @return 已校验模式的请求对象；非法值由 MVC 转换为请求体格式错误
     */
    @JsonCreator
    public static BuildKnowledgeDocumentRequest fromJson(@JsonProperty("mode") Object mode) {
        if (mode == null) {
            return new BuildKnowledgeDocumentRequest(null);
        }
        if (!(mode instanceof String value)) {
            throw new IllegalArgumentException("分块模式必须是 FLAT 或 PARENT_CHILD");
        }
        return new BuildKnowledgeDocumentRequest(ChunkingMode.valueOf(value));
    }

    /** 返回实际使用的模式，与应用服务两参数构建入口的默认行为一致。 */
    public ChunkingMode effectiveMode() {
        return mode == null ? ChunkingMode.FLAT : mode;
    }
}
