package com.item.aisummary.infrastructure.file.persistence.repository;

import com.item.aisummary.application.file.port.FileRepository;
import com.item.aisummary.domain.file.model.FileAsset;
import com.item.aisummary.infrastructure.file.persistence.entity.FileEntity;
import com.item.aisummary.infrastructure.file.persistence.mapper.FileMapper;
import com.item.aisummary.infrastructure.persistence.typehandler.UtcInstantTypeHandler;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Optional;

/** 真 SQL 持久化适配；写入要求一行，删除时间来自数据库。 */
@Repository
public class MyBatisFileRepository implements FileRepository {
    private final FileMapper mapper;
    public MyBatisFileRepository(FileMapper mapper) { this.mapper = mapper; }
    @Override public FileAsset insert(FileAsset file) {
        // 对象已由应用上传；SQL 失败继续向外传播，本适配器不删除远端对象补偿。
        FileEntity entity = toEntity(file);
        if (mapper.insert(entity) != 1) throw new IllegalStateException("保存文件元数据失败");
        return toFile(entity);
    }
    @Override public Optional<FileAsset> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MyBatisFileRepository::toFile);
    }
    @Override public int softDelete(String id, String actorUserId, Instant updateAt) {
        // 应用先校验归属；SQL 只限制 ID 与未删除条件，不提供状态快照的并发保护。
        // 删除时间由数据库生成，更新审计字段由调用方提供；不改所有者或 status。
        var update = new UpdateWrapper<FileEntity>().eq("id", id).isNull("deleted_at")
            .setSql("deleted_at = CURRENT_TIMESTAMP(6)").set("update_by", actorUserId)
            .set("update_at", updateAt, "typeHandler=" + UtcInstantTypeHandler.class.getName());
        return mapper.update(null, update);
    }
    private static FileEntity toEntity(FileAsset file) {
        FileEntity entity = new FileEntity();
        entity.id = file.id(); entity.originName = file.originName(); entity.mime = file.mime(); entity.size = file.size();
        entity.storageType = file.storageType(); entity.storage = file.storage(); entity.sha256 = file.sha256();
        entity.status = file.status(); entity.deletedAt = file.deletedAt(); entity.uploadStatus = file.uploadStatus();
        entity.createBy = file.createBy(); entity.updateBy = file.updateBy();
        entity.createAt = file.createAt(); entity.updateAt = file.updateAt();
        return entity;
    }
    private static FileAsset toFile(FileEntity entity) {
        return new FileAsset(entity.id, entity.originName, entity.mime, entity.size, entity.storageType, entity.storage,
            entity.sha256, entity.status, entity.deletedAt, entity.uploadStatus, entity.createBy, entity.updateBy,
            entity.createAt, entity.updateAt);
    }
}
