package com.item.aisummary.application.file.port;

/** 仅根据已启用的服务端配置路由；客户端不能选择 Bucket。 */
public interface ObjectStorageRouter {
    /**
     * 根据客户端指定的存储类型名称解析对应的存储桶配置与客户端实例。
     *
     * @param storageType 存储类型名称（如 "minio" 或 "aliyun"）
     * @return 包含目标 Bucket、存储客户端及新上传专用目录前缀的 StorageTarget
     * @throws com.item.aisummary.domain.file.exception.FileValidationException 存储类型非法或未启用
     */
    StorageTarget resolve(String storageType);

    /** 已解析的存储目标；目录前缀只参与新上传 Key 的生成，读取、签名和删除使用已存 Key。 */
    record StorageTarget(
            /** 服务端配置的 Bucket 名称。 */
            String bucket,
            /** 封装厂商 SDK 的对象存储客户端。 */
            ObjectStorageClient client,
            /** 新上传时叠加在公共文件目录前的厂商目录，不含首尾斜杠；空串表示不叠加。 */
            String keyPrefix) {
        /** 无额外厂商目录的存储目标，沿用公共文件目录。 */
        public StorageTarget(String bucket, ObjectStorageClient client) {
            this(bucket, client, "");
        }
    }
}
