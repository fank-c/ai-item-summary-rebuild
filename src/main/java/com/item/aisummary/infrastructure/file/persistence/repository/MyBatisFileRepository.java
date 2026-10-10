package com.item.aisummary.infrastructure.file.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.item.aisummary.application.file.port.FileRepository;
import com.item.aisummary.domain.file.model.FileAsset;
import com.item.aisummary.infrastructure.file.persistence.entity.FileEntity;
import com.item.aisummary.infrastructure.file.persistence.mapper.FileMapper;
import com.item.aisummary.infrastructure.persistence.typehandler.UtcInstantTypeHandler;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 真 SQL 持久化适配器；写入要求一行，删除时间由数据库生成。 */
@Repository
public class MyBatisFileRepository implements FileRepository {
    private final FileMapper mapper;

    public MyBatisFileRepository(FileMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 插入文件元数据到数据库。
     *
     * @param file 文件领域实体
     * @return 插入成功后的文件领域实体
     * @throws IllegalStateException 受影响行数不为 1
     */
    @Override
    public FileAsset insert(FileAsset file) {
        // 对象已由应用上传；SQL 失败继续向外传播，本适配器不删除远端对象补偿。
        FileEntity entity = toEntity(file);
        if (mapper.insert(entity) != 1) {
            throw new IllegalStateException("保存文件元数据失败");
        }
        return toFile(entity);
    }

    /**
     * 根据文件 ID 查询未被逻辑删除的文件记录。
     *
     * @param id 文件唯一标识
     * @return 包含领域实体的 Optional，若不存在或已逻辑删除则为空
     */
    @Override
    public Optional<FileAsset> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MyBatisFileRepository::toFile);
    }

    /**
     * 执行逻辑删除，将 deleted_at 更新为数据库当前 UTC 时间戳。
     *
     * @param id 文件唯一标识
     * @param actorUserId 操作人用户 ID
     * @param updateAt 审计更新时间
     * @return 数据库受影响行数
     */
    @Override
    public int softDelete(String id, String actorUserId, Instant updateAt) {
        // 应用先校验归属；SQL 只限制 ID 与未删除条件，不提供状态快照的并发保护。
        // 删除时间由数据库生成，更新审计字段由调用方提供；不改所有者或 status。
        var update =
                new UpdateWrapper<FileEntity>()
                        .eq("id", id)
                        .isNull("deleted_at")
                        .setSql("deleted_at = CURRENT_TIMESTAMP(6)")
                        .set("update_by", actorUserId)
                        .set(
                                "update_at",
                                updateAt,
                                "typeHandler=" + UtcInstantTypeHandler.class.getName());
        return mapper.update(null, update);
    }

    /** 将领域 FileAsset 转换为持久化实体 FileEntity。 */
    private static FileEntity toEntity(FileAsset file) {
        FileEntity entity = new FileEntity();
        entity.id = file.id();
        entity.originName = file.originName();
        entity.mime = file.mime();
        entity.size = file.size();
        entity.storageType = file.storageType();
        entity.storage = file.storage();
        entity.sha256 = file.sha256();
        entity.status = file.status();
        entity.deletedAt = file.deletedAt();
        entity.uploadStatus = file.uploadStatus();
        entity.createBy = file.createBy();
        entity.updateBy = file.updateBy();
        entity.createAt = file.createAt();
        entity.updateAt = file.updateAt();
        return entity;
    }

    /** 将持久化实体 FileEntity 转换为领域 FileAsset。 */
    private static FileAsset toFile(FileEntity entity) {
        return new FileAsset(
                entity.id,
                entity.originName,
                entity.mime,
                entity.size,
                entity.storageType,
                entity.storage,
                entity.sha256,
                entity.status,
                entity.deletedAt,
                entity.uploadStatus,
                entity.createBy,
                entity.updateBy,
                entity.createAt,
                entity.updateAt);
    }
}
