package com.item.aisummary.infrastructure.file.storage;

import com.item.aisummary.application.file.port.ObjectStorageClient;
import com.item.aisummary.application.file.port.ObjectStorageRouter;
import com.item.aisummary.domain.file.exception.FileValidationException;
import com.item.aisummary.infrastructure.file.config.FileProperties;
import java.util.Map;

/** 仅接受明确启用的 minio/aliyun；不自动选择其他厂商。 */
public final class ConfiguredStorageRouter implements ObjectStorageRouter {
    private final FileProperties properties;
    private final Map<String, ObjectStorageClient> clients;
    public ConfiguredStorageRouter(FileProperties properties, Map<String, ObjectStorageClient> clients) {
        this.properties = properties; this.clients = Map.copyOf(clients);
    }
    @Override public StorageTarget resolve(String type) {
        var config = properties.storage();
        if ("minio".equals(type) && config.minio().enabled() && clients.containsKey("minio")) {
            return new StorageTarget(config.minio().bucket(), clients.get("minio"));
        }
        if ("aliyun".equals(type) && config.aliyun().enabled() && clients.containsKey("aliyun")) {
            return new StorageTarget(config.aliyun().bucket(), clients.get("aliyun"));
        }
        throw new FileValidationException("文件存储类型不合法或未启用");
    }
}
