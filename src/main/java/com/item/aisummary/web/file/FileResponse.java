package com.item.aisummary.web.file;

import com.item.aisummary.domain.file.FileAsset;
import java.time.Instant;

/** 固定七字段，避免公开存储位置、摘要和审计身份。 */
public record FileResponse(String id, String originName, String mime, long size, String uploadStatus,
                           Instant createAt, Instant updateAt) {
    static FileResponse from(FileAsset file) {
        return new FileResponse(file.id(), file.originName(), file.mime(), file.size(), file.uploadStatus(),
            file.createAt(), file.updateAt());
    }
}
