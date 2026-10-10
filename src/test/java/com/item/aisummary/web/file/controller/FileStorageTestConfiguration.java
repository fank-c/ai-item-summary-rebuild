package com.item.aisummary.web.file.controller;

import com.item.aisummary.application.file.port.ObjectStorageClient;
import com.item.aisummary.application.file.port.ObjectStorageException;
import com.item.aisummary.application.file.port.ObjectStorageRouter;
import com.item.aisummary.domain.file.exception.FileValidationException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** 仅替换外部存储，数据库和应用服务保持真实；流关闭和失败副作用可观测。 */
@TestConfiguration(proxyBeanMethods = false)
public class FileStorageTestConfiguration {
    @Bean @Primary
    public MemoryStorage memoryStorage() { return new MemoryStorage(); }

    public static class MemoryStorage implements ObjectStorageRouter, ObjectStorageClient {
        public final Map<String, byte[]> objects = new HashMap<>();
        public boolean failPut, failDelete, failRead, failSign;
        public int opened, closed;
        public Duration expiration;
        public Runnable beforeDelete = () -> { };
        public void reset() {
            objects.clear(); failPut = false; failDelete = false; failRead = false; failSign = false;
            opened = 0; closed = 0; expiration = null; beforeDelete = () -> { };
        }
        @Override public StorageTarget resolve(String type) {
            if (!"minio".equals(type)) throw new FileValidationException("存储类型未启用");
            return new StorageTarget("test-bucket", this);
        }
        @Override public void putObject(String bucket, String key, String mime, long size, String sha, InputStream stream) {
            if (failPut) throw new ObjectStorageException("secret SDK failure");
            try { objects.put(key, stream.readAllBytes()); }
            catch (IOException e) { throw new ObjectStorageException("读取失败", e); }
        }
        @Override public InputStream openObject(String bucket, String key) {
            if (failRead || !objects.containsKey(key)) throw new ObjectStorageException("secret read failure");
            opened++;
            return new ByteArrayInputStream(objects.get(key)) {
                @Override public void close() throws IOException { closed++; super.close(); }
            };
        }
        @Override public String presignGetObject(String bucket, String key, Duration expiry) {
            if (failSign) throw new ObjectStorageException("secret signing failure");
            expiration = expiry;
            return "https://storage.example.test/" + bucket + "/" + key + "?temporary-signature";
        }
        @Override public void deleteObject(String bucket, String key) {
            beforeDelete.run();
            if (failDelete) throw new ObjectStorageException("secret delete failure");
            objects.remove(key);
        }
    }
}
