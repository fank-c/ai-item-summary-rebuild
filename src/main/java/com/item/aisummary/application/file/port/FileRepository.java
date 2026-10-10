package com.item.aisummary.application.file.port;

import com.item.aisummary.domain.file.model.FileAsset;
import java.time.Instant;
import java.util.Optional;

/** 文件持久化合同；逻辑删除不修改 status。 */
public interface FileRepository {
    /**
     * 插入文件元数据记录。
     *
     * <p>必须在对象存储上传成功后调用。若写入受影响行数不为 1 则抛出运行时异常。
     *
     * @param file 文件元数据领域实体
     * @return 插入成功后的文件实体
     */
    FileAsset insert(FileAsset file);

    /**
     * 根据 ID 查询未逻辑删除的文件元数据。
     *
     * <p>若记录不存在或已被逻辑删除（deleted_at 非空）则返回 Optional.empty()。
     *
     * @param id 文件唯一标识
     * @return 包含文件实体的 Optional，不存在或已删除时为空
     */
    Optional<FileAsset> findById(String id);

    /**
     * 对文件进行逻辑删除。
     *
     * <p>将 deleted_at 设置为数据库当前时间，同时记录操作人 update_by 与更新时间 update_at。
     *
     * @param id 文件唯一标识
     * @param actorUserId 执行删除操作的用户 ID
     * @param updateAt 本次更新时间戳
     * @return 数据库受影响行数（预期为 1）
     */
    int softDelete(String id, String actorUserId, Instant updateAt);
}
