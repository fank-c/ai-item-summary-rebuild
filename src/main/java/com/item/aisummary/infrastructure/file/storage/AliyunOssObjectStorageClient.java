package com.item.aisummary.infrastructure.file.storage;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.item.aisummary.application.file.port.ObjectStorageClient;
import com.item.aisummary.application.file.port.ObjectStorageException;
import com.item.aisummary.infrastructure.file.config.FileProperties;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

/** OSS 四项对象操作的 SDK 适配器，读取流关闭时同时释放 OSS 响应对象。 */
public final class AliyunOssObjectStorageClient implements ObjectStorageClient, AutoCloseable {
    private final OSS client;

    public AliyunOssObjectStorageClient(OSS client) {
        this.client = client;
    }

    /**
     * 根据已校验配置创建 OSS 客户端，并关闭 SDK 自动重试。
     *
     * @param properties 存储配置属性
     * @return 初始化的阿里云 OSS 对象存储适配器
     */
    public static AliyunOssObjectStorageClient create(FileProperties.Storage properties) {
        var aliyun = properties.aliyun();
        var config = new ClientBuilderConfiguration();
        // OSS 没有独立写入超时；连接等待复用连接超时，不额外引入重试层。
        config.setConnectionTimeout(FileProperties.timeoutMillis(properties.connectTimeout()));
        config.setConnectionRequestTimeout(
                FileProperties.timeoutMillis(properties.connectTimeout()));
        config.setSocketTimeout(FileProperties.timeoutMillis(properties.readTimeout()));
        config.setMaxErrorRetry(0);

        OSS client =
                new OSSClientBuilder()
                        .build(
                                aliyun.endpoint(),
                                aliyun.accessKeyId(),
                                aliyun.accessKeySecret(),
                                config);
        return new AliyunOssObjectStorageClient(client);
    }

    /**
     * 上传二进制对象并携带大小、MIME 和 SHA-256 元数据。
     *
     * @param bucket 存储空间名称
     * @param key 对象唯一 Key
     * @param mime MIME 类型
     * @param size 文件字节大小
     * @param sha256 文件 SHA-256 摘要
     * @param stream 二进制内容输入流
     * @throws ObjectStorageException OSS 上传异常
     */
    @Override
    public void putObject(
            String bucket, String key, String mime, long size, String sha256, InputStream stream) {
        var metadata = new ObjectMetadata();
        // 使用应用核验后的大小和摘要；SHA-256 写入元数据，不作为远端内容核对。
        metadata.setContentLength(size);
        metadata.setContentType(mime);
        metadata.setUserMetadata(Map.of("sha256", sha256));
        try {
            client.putObject(bucket, key, stream, metadata);
        } catch (Exception exception) {
            throw failure("上传", exception);
        }
    }

    /**
     * 打开 OSS 对象二进制流，关闭流时同时释放底层 SDK 资源。
     *
     * @param bucket 存储空间名称
     * @param key 对象 Key
     * @return 内容输入流；调用方负责关闭
     * @throws ObjectStorageException OSS 读取异常
     */
    @Override
    public InputStream openObject(String bucket, String key) {
        try {
            var object = client.getObject(bucket, key);
            // 调用者拿到流后拥有释放责任；重复关闭只释放一次 SDK 对象。
            return new FilterInputStream(object.getObjectContent()) {
                private boolean closed;

                @Override
                public void close() throws IOException {
                    if (!closed) {
                        closed = true;
                        // OSSObject.close 自身负责关闭内容流和底层响应。
                        object.close();
                    }
                }
            };
        } catch (Exception exception) {
            throw failure("读取", exception);
        }
    }

    /**
     * 生成 GET 操作的预签名下载地址。
     *
     * @param bucket 存储空间名称
     * @param key 对象 Key
     * @param expiration 凭据有效期
     * @return 预签名 URL 字符串
     * @throws ObjectStorageException 签名生成异常
     */
    @Override
    public String presignGetObject(String bucket, String key, Duration expiration) {
        try {
            return client.generatePresignedUrl(
                            bucket, key, Date.from(Instant.now().plus(expiration)))
                    .toExternalForm();
        } catch (Exception exception) {
            throw failure("下载签名", exception);
        }
    }

    /**
     * 删除 OSS 中的指定对象。
     *
     * @param bucket 存储空间名称
     * @param key 待删除对象 Key
     * @throws ObjectStorageException 删除操作异常
     */
    @Override
    public void deleteObject(String bucket, String key) {
        try {
            client.deleteObject(bucket, key);
        } catch (Exception exception) {
            throw failure("删除", exception);
        }
    }

    /** Spring 关闭容器时释放共享 SDK 客户端，单个内容流由调用者负责关闭。 */
    @Override
    public void close() {
        client.shutdown();
    }

    private static ObjectStorageException failure(String operation, Exception cause) {
        return new ObjectStorageException("OSS 对象" + operation + "失败", cause);
    }
}
