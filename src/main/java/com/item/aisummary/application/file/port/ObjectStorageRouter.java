package com.item.aisummary.application.file.port;

/** 仅根据已启用的服务端配置路由；客户端不能选择 Bucket。 */
public interface ObjectStorageRouter {
    StorageTarget resolve(String storageType);
    record StorageTarget(String bucket, ObjectStorageClient client) { }
}
