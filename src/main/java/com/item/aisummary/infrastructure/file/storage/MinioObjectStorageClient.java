package com.item.aisummary.infrastructure.file.storage;

import com.item.aisummary.application.file.port.ObjectStorageClient;
import com.item.aisummary.application.file.port.ObjectStorageException;
import com.item.aisummary.infrastructure.file.config.FileProperties;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

/** MinIO 四项对象操作的 SDK 适配器，不额外提供重试、探测或业务补偿。 */
public final class MinioObjectStorageClient implements ObjectStorageClient, AutoCloseable {
    private final MinioClient client;

    public MinioObjectStorageClient(MinioClient client) {
        this.client = client;
    }

    /**
     * 根据已校验配置创建 MinIO 客户端，并关闭连接失败自动重试。
     *
     * @param properties 存储配置属性
     * @return 初始化的 MinIO 对象存储适配器
     */
    public static MinioObjectStorageClient create(FileProperties.Storage properties) {
        var minio = properties.minio();
        // 这些超时约束单阶段网络操作，不代表整个文件流处理的总时限。
        var http =
                new OkHttpClient.Builder()
                        .connectTimeout(
                                FileProperties.timeoutMillis(properties.connectTimeout()),
                                TimeUnit.MILLISECONDS)
                        .readTimeout(
                                FileProperties.timeoutMillis(properties.readTimeout()),
                                TimeUnit.MILLISECONDS)
                        .writeTimeout(
                                FileProperties.timeoutMillis(properties.writeTimeout()),
                                TimeUnit.MILLISECONDS)
                        .retryOnConnectionFailure(false)
                        .build();

        var builder =
                MinioClient.builder()
                        .endpoint(minio.endpoint())
                        .credentials(minio.accessKey(), minio.secretKey())
                        .httpClient(http, true);
        if (minio.region() != null && !minio.region().isBlank()) {
            builder.region(minio.region());
        }

        MinioClient client = builder.build();
        if (minio.pathStyleAccess()) {
            client.disableVirtualStyleEndpoint();
        } else {
            client.enableVirtualStyleEndpoint();
        }
        return new MinioObjectStorageClient(client);
    }

    /**
     * 上传二进制对象并携带 SHA-256 元数据。
     *
     * @param bucket 目标桶名称
     * @param key 对象唯一 Key
     * @param mime MIME 类型
     * @param size 文件字节大小
     * @param sha256 文件 SHA-256 摘要
     * @param stream 二进制内容输入流
     * @throws ObjectStorageException MinIO 上传异常
     */
    @Override
    public void putObject(
            String bucket, String key, String mime, long size, String sha256, InputStream stream) {
        try {
            // 大小来自应用完整读取结果；SHA-256 作为元数据保存，不发起上传后核对。
            client.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .contentType(mime)
                            .userMetadata(Map.of("sha256", sha256))
                            .stream(stream, size, -1)
                            .build());
        } catch (Exception exception) {
            throw failure("上传", exception);
        }
    }

    /**
     * 打开 MinIO 对象二进制流。
     *
     * @param bucket 存储桶名称
     * @param key 对象 Key
     * @return 内容输入流；调用方负责关闭
     * @throws ObjectStorageException MinIO 读取异常
     */
    @Override
    public InputStream openObject(String bucket, String key) {
        // SDK 响应流直接交给上层；调用者关闭流时才释放底层 HTTP 资源。
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception exception) {
            throw failure("读取", exception);
        }
    }

    /**
     * 生成 GET 操作的预签名下载地址。
     *
     * @param bucket 存储桶名称
     * @param key 对象 Key
     * @param expiration 凭据有效期
     * @return 预签名 URL 字符串
     * @throws ObjectStorageException 签名计算异常
     */
    @Override
    public String presignGetObject(String bucket, String key, Duration expiration) {
        try {
            return client.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucket)
                            .object(key)
                            .expiry(Math.toIntExact(expiration.toSeconds()), TimeUnit.SECONDS)
                            .build());
        } catch (Exception exception) {
            throw failure("下载签名", exception);
        }
    }

    /**
     * 删除 MinIO 中的指定对象。
     *
     * @param bucket 存储桶名称
     * @param key 待删除对象 Key
     * @throws ObjectStorageException 删除操作异常
     */
    @Override
    public void deleteObject(String bucket, String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception exception) {
            throw failure("删除", exception);
        }
    }

    /** Spring 容器销毁适配器时关闭共享客户端，不代替逐个关闭内容流。 */
    @Override
    public void close() throws Exception {
        client.close();
    }

    private static ObjectStorageException failure(String operation, Exception cause) {
        return new ObjectStorageException("MinIO 对象" + operation + "失败", cause);
    }
}
