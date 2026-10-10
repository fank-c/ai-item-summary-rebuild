package com.item.aisummary.application.file.port;

import com.item.aisummary.domain.file.model.FileAsset;
import java.time.Instant;
import java.util.Optional;

/** 文件持久化合同；逻辑删除不修改 status。 */
public interface FileRepository {
    FileAsset insert(FileAsset file);
    /** 不存在或已删除时为空，status、完成状态和所有者仍由应用用例校验。 */
    Optional<FileAsset> findById(String id);
    /** 仅更新删除时间与更新审计字段；所有者不变，返回行数由应用用例检查。 */
    int softDelete(String id, String actorUserId, Instant updateAt);
}
