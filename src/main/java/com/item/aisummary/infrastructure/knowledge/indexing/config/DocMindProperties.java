package com.item.aisummary.infrastructure.knowledge.indexing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 阿里云文档智能（Document Mind）可选 PDF 云端解析与 OCR 属性配置。
 *
 * <p>绑定前缀 {@code app.knowledge.rag.docmind}，仅在知识库构建且显式启用 DocMind 时生效。
 *
 * <p>安全与配置约束：
 * <ul>
 *   <li>凭据保护：启用状态下必须配置非空的 {@code accessKeyId} 与 {@code accessKeySecret}；{@link #toString()} 自动脱敏凭据，防止日志泄漏。</li>
 *   <li>轮询时限：等待超时 {@code waitTimeout} 最大 1 小时，轮询间隔 {@code pollInterval} 最大 1 分钟，两者均要求为正时间段。</li>
 *   <li>模型增强：{@code vlmEnhancement}（视觉大模型增强）默认开启，可显著提升复杂多栏、图表与扫描件识别质量，但会增加单页费用与处理耗时。</li>
 * </ul>
 *
 * @param enabled 是否显式启用阿里云文档智能 PDF 云端解析（默认 false，关闭时不向外发起任何请求）
 * @param accessKeyId 阿里云访问密钥 ID（启用时必填）
 * @param accessKeySecret 阿里云访问密钥 Secret（启用时必填，禁止打印到日志）
 * @param endpoint 文档智能 API 接入点域名（默认 docmind-api.cn-hangzhou.aliyuncs.com）
 * @param vlmEnhancement 是否启用视觉大模型（VLM）增强（默认 true）
 * @param formulaEnhancement 是否启用公式识别增强（默认 false）
 * @param waitTimeout 异步解析任务的最长等待截止时间（默认 600s，范围 1ms ~ 1h）
 * @param pollInterval 异步任务状态轮询间隔（默认 2s，范围 1ms ~ 1min）
 */
@ConfigurationProperties("app.knowledge.rag.docmind")
public record DocMindProperties(
        @DefaultValue("false") boolean enabled,
        String accessKeyId,
        String accessKeySecret,
        @DefaultValue("docmind-api.cn-hangzhou.aliyuncs.com") String endpoint,
        @DefaultValue("true") boolean vlmEnhancement,
        @DefaultValue("false") boolean formulaEnhancement,
        @DefaultValue("600s") java.time.Duration waitTimeout,
        @DefaultValue("2s") java.time.Duration pollInterval) {

    public DocMindProperties {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("DocMind endpoint 不能为空");
        }
        if (waitTimeout == null || waitTimeout.isNegative() || waitTimeout.isZero()
                || pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("DocMind 轮询时限必须为正数");
        }
        if (waitTimeout.compareTo(java.time.Duration.ofHours(1)) > 0
                || pollInterval.compareTo(java.time.Duration.ofMinutes(1)) > 0
                || waitTimeout.toMillis() < 1 || pollInterval.toMillis() < 1) {
            throw new IllegalArgumentException("DocMind 轮询时限超过允许范围");
        }
        if (enabled && (accessKeyId == null || accessKeyId.isBlank()
                || accessKeySecret == null || accessKeySecret.isBlank())) {
            throw new IllegalArgumentException("启用 DocMind 时必须配置 AccessKey ID 和 Secret");
        }
    }

    @Override public String toString() {
        return "DocMindProperties[enabled=" + enabled + ", credentials=REDACTED]";
    }
}
