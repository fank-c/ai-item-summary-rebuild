package com.item.aisummary.infrastructure.file.storage;

import com.item.aisummary.application.file.port.ObjectStorageClient;
import com.item.aisummary.application.file.port.ObjectStorageException;
import com.item.aisummary.infrastructure.file.FileProperties;
import io.minio.*;
import io.minio.http.Method;
import okhttp3.OkHttpClient;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** MinIO 四操作 SDK 适配；没有失败核对、重试包装和业务补偿。 */
public final class MinioObjectStorageClient implements ObjectStorageClient, AutoCloseable {
    private final MinioClient client;
    public MinioObjectStorageClient(MinioClient client) { this.client = client; }

    public static MinioObjectStorageClient create(FileProperties.Storage properties) {
        var minio = properties.minio();
        var http = new OkHttpClient.Builder()
            .connectTimeout(FileProperties.timeoutMillis(properties.connectTimeout()), TimeUnit.MILLISECONDS)
            .readTimeout(FileProperties.timeoutMillis(properties.readTimeout()), TimeUnit.MILLISECONDS)
            .writeTimeout(FileProperties.timeoutMillis(properties.writeTimeout()), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false).build();
        var builder = MinioClient.builder().endpoint(minio.endpoint()).credentials(minio.accessKey(), minio.secretKey())
            .httpClient(http, true);
        if (minio.region() != null && !minio.region().isBlank()) builder.region(minio.region());
        var client = builder.build();
        if (minio.pathStyleAccess()) client.disableVirtualStyleEndpoint(); else client.enableVirtualStyleEndpoint();
        return new MinioObjectStorageClient(client);
    }

    @Override public void putObject(String bucket, String key, String mime, long size, String sha256, InputStream stream) {
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType(mime)
                .userMetadata(Map.of("sha256", sha256)).stream(stream, size, -1).build());
        } catch (Exception exception) { throw failure("上传", exception); }
    }
    @Override public InputStream openObject(String bucket, String key) {
        try { return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch (Exception exception) { throw failure("读取", exception); }
    }
    @Override public String presignGetObject(String bucket, String key, Duration expiration) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET).bucket(bucket)
                .object(key).expiry(Math.toIntExact(expiration.toSeconds()), TimeUnit.SECONDS).build());
        } catch (Exception exception) { throw failure("下载签名", exception); }
    }
    @Override public void deleteObject(String bucket, String key) {
        try { client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch (Exception exception) { throw failure("删除", exception); }
    }
    @Override public void close() throws Exception { client.close(); }
    private static ObjectStorageException failure(String operation, Exception cause) {
        return new ObjectStorageException("MinIO 对象" + operation + "失败", cause);
    }
}
