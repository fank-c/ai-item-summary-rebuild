package com.item.aisummary.domain.file.model;

import java.time.Instant;

/** 文件元数据；createBy 为不可变所有者，删除与启用状态独立。 */
public record FileAsset(
        /** 文件 ID；应用层生成的业务标识。 */
        String id,
        /** 文件上传时的原始展示名称。 */
        String originName,
        /** 规范化后的 MIME 类型。 */
        String mime,
        /** 实际文件大小，单位为字节。 */
        long size,
        /** 对象存储路由类型。 */
        String storageType,
        /** 对象存储 Key。 */
        String storage,
        /** 实际文件内容的 SHA-256 摘要。 */
        String sha256,
        /** 文件业务状态。 */
        int status,
        /** 逻辑删除时间；为 null 表示未删除。 */
        Instant deletedAt,
        /** 上传生命周期状态。 */
        String uploadStatus,
        /** 不可变的文件所有者 ID。 */
        String createBy,
        /** 最后更新记录的用户 ID。 */
        String updateBy,
        /** 文件记录创建时间。 */
        Instant createAt,
        /** 文件记录最后更新时间。 */
        Instant updateAt) {

    /**
     * 判断文件是否属于指定用户且处于可处理的有效状态。
     *
     * <p>必须同时满足四个独立条件：未被逻辑删除、业务状态 status=1、上传状态 completed、所有者 create_by 与调用者匹配。
     *
     * @param actorUserId 调用方用户 ID
     * @return 若四项条件全部满足则返回 true，否则返回 false
     */
    public boolean isOwnedProcessable(String actorUserId) {
        return deletedAt == null
                && status == 1
                && "completed".equals(uploadStatus)
                && actorUserId.equals(createBy);
    }
}
