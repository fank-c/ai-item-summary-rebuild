package com.item.aisummary.web.file.response;

import com.item.aisummary.domain.file.model.FileAsset;
import java.time.Instant;

/** 固定七字段，避免公开存储位置、摘要和审计身份。 */
public record FileResponse(
        /** 文件资源 ID。 */
        String id,
        /** 文件原始展示名称。 */
        String originName,
        /** 规范化后的 MIME 类型。 */
        String mime,
        /** 实际文件大小，单位为字节。 */
        long size,
        /** 上传生命周期状态。 */
        String uploadStatus,
        /** 文件记录创建时间。 */
        Instant createAt,
        /** 文件记录最后更新时间。 */
        Instant updateAt) {

    /**
     * 将文件领域对象转换为 HTTP 响应记录。
     *
     * <p>仅暴露固定七字段，隐藏存储类型、物理 Key、SHA-256 和所有者 ID。
     *
     * @param file 文件领域实体
     * @return 映射后的 HTTP 响应记录
     */
    public static FileResponse from(FileAsset file) {
        return new FileResponse(
                file.id(),
                file.originName(),
                file.mime(),
                file.size(),
                file.uploadStatus(),
                file.createAt(),
                file.updateAt());
    }
}
