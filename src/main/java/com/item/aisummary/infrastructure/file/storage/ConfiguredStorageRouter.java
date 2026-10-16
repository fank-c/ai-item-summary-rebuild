package com.item.aisummary.infrastructure.file.storage;

import com.item.aisummary.application.file.port.ObjectStorageClient;
import com.item.aisummary.application.file.port.ObjectStorageRouter;
import com.item.aisummary.domain.file.exception.FileValidationException;
import com.item.aisummary.infrastructure.file.config.FileProperties;
import java.util.Map;

/** 仅接受明确启用的 minio/aliyun，不自动选择其他厂商或回退配置。 */
public final class ConfiguredStorageRouter implements ObjectStorageRouter {
    private final FileProperties properties;
    private final Map<String, ObjectStorageClient> clients;

    public ConfiguredStorageRouter(
            FileProperties properties, Map<String, ObjectStorageClient> clients) {
        this.properties = properties;
        this.clients = Map.copyOf(clients);
    }

    /**
     * 根据请求的存储类型路由到已启用的存储适配器。
     *
     * @param type 存储类型（"minio" 或 "aliyun"）
     * @return 目标存储桶、客户端和新上传专用目录；OSS 使用项目目录，MinIO 无额外目录
     * @throws FileValidationException 存储类型非法或目标存储未启用
     */
    @Override
    public StorageTarget resolve(String type) {
        var config = properties.storage();
        if ("minio".equals(type) && config.minio().enabled() && clients.containsKey("minio")) {
            return new StorageTarget(config.minio().bucket(), clients.get("minio"));
        }
        if ("aliyun".equals(type) && config.aliyun().enabled() && clients.containsKey("aliyun")) {
            return new StorageTarget(
                    config.aliyun().bucket(), clients.get("aliyun"), config.aliyun().keyPrefix());
        }
        throw new FileValidationException("文件存储类型不合法或未启用");
    }
}
