package com.item.aisummary.application.file.port;

import com.item.aisummary.domain.file.FileAsset;
import java.time.Instant;
import java.util.Optional;

/** 文件持久化合同；逻辑删除不修改 status。 */
public interface FileRepository {
    FileAsset insert(FileAsset file);
    Optional<FileAsset> findById(String id);
    int softDelete(String id, String actorUserId, Instant updateAt);
}
