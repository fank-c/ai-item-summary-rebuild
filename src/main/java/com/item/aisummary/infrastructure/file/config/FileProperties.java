package com.item.aisummary.infrastructure.file.config;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** 文件约束和可选存储配置；只在启用厂商时要求对应凭据。 */
@ConfigurationProperties("app.file")
public record FileProperties(
        /** 文件上传和名称校验配置。 */
        @DefaultValue Upload upload,
        /** 对象存储路由、期限和厂商连接配置。 */
        @DefaultValue Storage storage) {

    public record Upload(
            /** 单个文件的实际字节数上限，同时用于业务校验。 */
            @DefaultValue("20MB") DataSize maxSize,
            /** 允许的文件扩展名白名单。 */
            @DefaultValue({"txt", "md", "pdf", "docx"}) Set<String> allowedExtensions) {
        public Upload {
            if (maxSize == null || maxSize.toBytes() <= 0) {
                throw new IllegalArgumentException("文件大小上限必须为正数");
            }
            if (allowedExtensions == null
                    || allowedExtensions.isEmpty()
                    || allowedExtensions.stream()
                            .anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("文件扩展名白名单不能为空");
            }
            allowedExtensions = Set.copyOf(allowedExtensions);
        }
    }

    public record Storage(
            /** 新对象 Key 的路径前缀。 */
            @DefaultValue("files") String keyPrefix,
            /** 下载 URL 的有效期，范围为 1 秒到 7 天。 */
            @DefaultValue("15m") Duration downloadUrlExpiration,
            /** SDK 建立连接的超时时间。 */
            @DefaultValue("5s") Duration connectTimeout,
            /** SDK 读取数据的超时时间。 */
            @DefaultValue("60s") Duration readTimeout,
            /** MinIO 写入数据的超时时间。 */
            @DefaultValue("60s") Duration writeTimeout,
            /** MinIO 连接配置。 */
            @DefaultValue Minio minio,
            /** 阿里云 OSS 连接配置。 */
            @DefaultValue Aliyun aliyun) {
        public Storage {
            // 公共目录与厂商目录分别规范化，完整 Key 的字节预算在新上传时统一检查。
            keyPrefix = normalizeKeyPrefix(keyPrefix);
            if (downloadUrlExpiration == null
                    || downloadUrlExpiration.getNano() != 0
                    || downloadUrlExpiration.getSeconds() < 1
                    || downloadUrlExpiration.getSeconds() > 604800) {
                throw new IllegalArgumentException("下载期限必须为 1 到 604800 的整数秒");
            }
            timeoutMillis(connectTimeout);
            timeoutMillis(readTimeout);
            timeoutMillis(writeTimeout);
        }
    }

    public record Minio(
            /** 是否启用 MinIO 适配器；关闭时不要求其他连接字段。 */
            @DefaultValue("false") boolean enabled,
            /** MinIO HTTP 或 HTTPS endpoint。 */
            String endpoint,
            /** 预先创建的 Bucket 名称。 */
            String bucket,
            /** MinIO 区域。 */
            @DefaultValue("us-east-1") String region,
            /** MinIO 访问密钥 ID。 */
            String accessKey,
            /** MinIO 访问密钥；禁止写入日志。 */
            String secretKey,
            /** 是否使用路径样式访问。 */
            @DefaultValue("true") boolean pathStyleAccess) {
        public Minio {
            if (enabled) {
                FileProperties.endpoint(endpoint);
                required(bucket);
                required(accessKey);
                required(secretKey);
            }
        }

        // record 默认字符串会包含凭据；这里只保留开关信息供配置诊断。
        @Override
        public String toString() {
            return "Minio[enabled=" + enabled + "]";
        }
    }

    public record Aliyun(
            /** 是否启用阿里云 OSS 适配器；关闭时不要求其他连接字段。 */
            @DefaultValue("false") boolean enabled,
            /** 阿里云 OSS endpoint。 */
            String endpoint,
            /** 预先创建的 OSS Bucket 名称。 */
            String bucket,
            /** 阿里云 AccessKey ID。 */
            String accessKeyId,
            /** 阿里云 AccessKey Secret；禁止写入日志。 */
            String accessKeySecret,
            /** OSS 项目目录，叠加在公共文件目录前；主配置默认按 Spring 应用名称生成。 */
            @DefaultValue("item/ai-item-summary") String keyPrefix) {
        public Aliyun {
            if (enabled) {
                FileProperties.endpoint(endpoint);
                required(bucket);
                required(accessKeyId);
                required(accessKeySecret);
                keyPrefix = normalizeKeyPrefix(keyPrefix);
            }
        }

        // 同样避免隐式打印 OSS 凭据。
        @Override
        public String toString() {
            return "Aliyun[enabled=" + enabled + "]";
        }
    }

    /** 适配 SDK 的 int 毫秒参数，拒绝小数毫秒、非正值和溢出。 */
    public static int timeoutMillis(Duration duration) {
        if (duration == null
                || duration.isNegative()
                || duration.isZero()
                || duration.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("SDK 超时必须是正整数毫秒");
        }
        long millis = duration.toMillis();
        if (millis < 1 || millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("SDK 超时超出范围");
        }
        return (int) millis;
    }

    /** 规范化服务端目录，去掉首尾斜杠；每项配置最多 150 字符，最终 Key 另做统一预算校验。 */
    private static String normalizeKeyPrefix(String value) {
        if (value == null) {
            throw new IllegalArgumentException("对象 Key 前缀不能为空");
        }
        String normalized =
                value.trim().replace('\\', '/').replace("..", "_").replaceAll("^/+|/+$", "");
        if (normalized.isBlank() || normalized.length() > 150) {
            throw new IllegalArgumentException("对象 Key 前缀长度不合法");
        }
        return normalized;
    }

    private static void required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("已启用存储的连接配置不完整");
        }
    }

    private static void endpoint(String value) {
        required(value);
        URI uri = URI.create(value);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("存储 endpoint 必须为 HTTP 或 HTTPS 服务地址");
        }
    }
}
