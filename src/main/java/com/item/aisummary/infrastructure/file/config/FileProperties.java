package com.item.aisummary.infrastructure.file.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

/** 文件约束和可选存储配置；只在启用厂商时要求凭据。 */
@ConfigurationProperties("app.file")
public record FileProperties(@DefaultValue Upload upload, @DefaultValue Storage storage) {
    public record Upload(@DefaultValue("20MB") DataSize maxSize,
                         @DefaultValue({"txt", "md", "pdf", "docx"}) Set<String> allowedExtensions) {
        public Upload {
            if (maxSize == null || maxSize.toBytes() <= 0) throw new IllegalArgumentException("文件大小上限必须为正数");
            if (allowedExtensions == null || allowedExtensions.isEmpty()
                    || allowedExtensions.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("文件扩展名白名单不能为空");
            }
            allowedExtensions = Set.copyOf(allowedExtensions);
        }
    }
    public record Storage(@DefaultValue("files") String keyPrefix,
                          @DefaultValue("15m") Duration downloadUrlExpiration,
                          @DefaultValue("5s") Duration connectTimeout,
                          @DefaultValue("60s") Duration readTimeout,
                          @DefaultValue("60s") Duration writeTimeout,
                          @DefaultValue Minio minio, @DefaultValue Aliyun aliyun) {
        public Storage {
            // 只规范化配置前缀；完整 Key 的 UTF-8 字节预算仍在新上传时检查。
            if (keyPrefix == null) throw new IllegalArgumentException("对象 Key 前缀不能为空");
            keyPrefix = keyPrefix.trim().replace('\\', '/').replace("..", "_").replaceAll("^/+|/+$", "");
            if (keyPrefix.isBlank() || keyPrefix.length() > 150) throw new IllegalArgumentException("对象 Key 前缀长度不合法");
            if (downloadUrlExpiration == null || downloadUrlExpiration.getNano() != 0
                    || downloadUrlExpiration.getSeconds() < 1 || downloadUrlExpiration.getSeconds() > 604800) {
                throw new IllegalArgumentException("下载期限必须为 1 到 604800 的整数秒");
            }
            timeoutMillis(connectTimeout); timeoutMillis(readTimeout); timeoutMillis(writeTimeout);
        }
    }
    public record Minio(@DefaultValue("false") boolean enabled, String endpoint, String bucket,
                        @DefaultValue("us-east-1") String region, String accessKey, String secretKey,
                        @DefaultValue("true") boolean pathStyleAccess) {
        public Minio {
            if (enabled) { FileProperties.endpoint(endpoint); required(bucket); required(accessKey); required(secretKey); }
        }
        // record 默认字符串会包含凭据；这里只保留开关信息供配置诊断。
        @Override public String toString() { return "Minio[enabled=" + enabled + "]"; }
    }
    public record Aliyun(@DefaultValue("false") boolean enabled, String endpoint, String bucket,
                         String accessKeyId, String accessKeySecret) {
        public Aliyun {
            if (enabled) { FileProperties.endpoint(endpoint); required(bucket); required(accessKeyId); required(accessKeySecret); }
        }
        // 同样避免隐式打印 OSS 凭据。
        @Override public String toString() { return "Aliyun[enabled=" + enabled + "]"; }
    }
    /** 适配 SDK 的 int 毫秒参数，拒绝小数毫秒、非正值和溢出。 */
    public static int timeoutMillis(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()
                || duration.getNano() % 1_000_000 != 0) throw new IllegalArgumentException("SDK 超时必须是正整数毫秒");
        long millis = duration.toMillis();
        if (millis < 1 || millis > Integer.MAX_VALUE) throw new IllegalArgumentException("SDK 超时超出范围");
        return (int) millis;
    }
    private static void required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("已启用存储的连接配置不完整");
    }
    private static void endpoint(String value) {
        required(value);
        URI uri = URI.create(value);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("存储 endpoint 必须为 HTTP 或 HTTPS 服务地址");
        }
    }
}
