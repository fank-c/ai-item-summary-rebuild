package com.item.aisummary.domain.file;

import java.time.Instant;

/** 文件元数据；createBy 为不可变所有者，删除与启用状态独立。 */
public record FileAsset(String id, String originName, String mime, long size, String storageType,
                        String storage, String sha256, int status, Instant deletedAt, String uploadStatus,
                        String createBy, String updateBy, Instant createAt, Instant updateAt) {
    public boolean isOwnedProcessable(String actorUserId) {
        return deletedAt == null && status == 1 && "completed".equals(uploadStatus)
            && actorUserId.equals(createBy);
    }
}
