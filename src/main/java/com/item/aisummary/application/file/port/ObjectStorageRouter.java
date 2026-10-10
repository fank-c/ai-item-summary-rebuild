package com.item.aisummary.application.file.port;

/** 仅根据已启用的服务端配置路由；客户端不能选择 Bucket。 */
public interface ObjectStorageRouter {
    /**
     * 根据客户端指定的存储类型名称解析对应的存储桶配置与客户端实例。
     *
     * @param storageType 存储类型名称（如 "minio" 或 "aliyun"）
     * @return 包含目标 Bucket 和存储客户端的 StorageTarget
     * @throws com.item.aisummary.domain.file.exception.FileValidationException 存储类型非法或未启用
     */
    StorageTarget resolve(String storageType);

    /** 已解析的 Bucket 与客户端，调用方不得自行替换配置值。 */
    record StorageTarget(
            /** 服务端配置的 Bucket 名称。 */
            String bucket,
            /** 封装厂商 SDK 的对象存储客户端。 */
            ObjectStorageClient client) {}
}
