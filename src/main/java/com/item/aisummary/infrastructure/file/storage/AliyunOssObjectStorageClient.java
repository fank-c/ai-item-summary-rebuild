package com.item.aisummary.infrastructure.file.storage;

import com.item.aisummary.application.file.port.ObjectStorageClient;
import com.item.aisummary.application.file.port.ObjectStorageException;
import com.item.aisummary.infrastructure.file.config.FileProperties;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

/** OSS 四操作 SDK 适配；返回流关闭时连同 SDK 对象资源一起释放。 */
public final class AliyunOssObjectStorageClient implements ObjectStorageClient, AutoCloseable {
    private final OSS client;
    public AliyunOssObjectStorageClient(OSS client) { this.client = client; }

    public static AliyunOssObjectStorageClient create(FileProperties.Storage properties) {
        var aliyun = properties.aliyun();
        var config = new ClientBuilderConfiguration();
        // OSS 没有独立写入超时；连接等待复用连接超时，不额外引入重试层。
        config.setConnectionTimeout(FileProperties.timeoutMillis(properties.connectTimeout()));
        config.setConnectionRequestTimeout(FileProperties.timeoutMillis(properties.connectTimeout()));
        config.setSocketTimeout(FileProperties.timeoutMillis(properties.readTimeout()));
        config.setMaxErrorRetry(0);
        return new AliyunOssObjectStorageClient(new OSSClientBuilder().build(aliyun.endpoint(), aliyun.accessKeyId(),
            aliyun.accessKeySecret(), config));
    }
    @Override public void putObject(String bucket, String key, String mime, long size, String sha256, InputStream stream) {
        var metadata = new ObjectMetadata();
        // 使用应用核验后的大小和摘要；SHA-256 写入元数据，不作为远端内容核对。
        metadata.setContentLength(size); metadata.setContentType(mime); metadata.setUserMetadata(Map.of("sha256", sha256));
        try { client.putObject(bucket, key, stream, metadata); }
        catch (Exception exception) { throw failure("上传", exception); }
    }
    @Override public InputStream openObject(String bucket, String key) {
        try {
            var object = client.getObject(bucket, key);
            // 调用者拿到流后拥有释放责任；重复关闭只释放一次 SDK 对象。
            return new FilterInputStream(object.getObjectContent()) {
                private boolean closed;
                @Override public void close() throws IOException {
                    if (!closed) {
                        closed = true;
                        // OSSObject.close 自身负责关闭内容流和底层响应。
                        object.close();
                    }
                }
            };
        } catch (Exception exception) { throw failure("读取", exception); }
    }
    @Override public String presignGetObject(String bucket, String key, Duration expiration) {
        try { return client.generatePresignedUrl(bucket, key, Date.from(Instant.now().plus(expiration))).toExternalForm(); }
        catch (Exception exception) { throw failure("下载签名", exception); }
    }
    @Override public void deleteObject(String bucket, String key) {
        try { client.deleteObject(bucket, key); }
        catch (Exception exception) { throw failure("删除", exception); }
    }
    // Spring 关闭容器时释放共享 SDK 客户端，单个内容流的释放由其调用者负责。
    @Override public void close() { client.shutdown(); }
    private static ObjectStorageException failure(String operation, Exception cause) {
        return new ObjectStorageException("OSS 对象" + operation + "失败", cause);
    }
}
