package com.item.aisummary.application.file.port;

import java.io.InputStream;
import java.time.Duration;

/** 四项对象存储操作；上传流由调用者关闭，读取流的所有权交给调用者。 */
public interface ObjectStorageClient {
    void putObject(String bucket, String key, String mime, long size, String sha256, InputStream stream);
    InputStream openObject(String bucket, String key);
    String presignGetObject(String bucket, String key, Duration expiration);
    void deleteObject(String bucket, String key);
}
