package com.item.aisummary.infrastructure.file.persistence;

import com.baomidou.mybatisplus.annotation.*;
import com.item.aisummary.infrastructure.user.persistence.UtcInstantTypeHandler;
import java.time.Instant;

/** file_asset 表映射；领域类型不依赖 MyBatis。直传过期列保留为空。 */
@TableName(value = "file_asset", autoResultMap = true)
public final class FileEntity {
    @TableId(type = IdType.INPUT) public String id;
    public String originName;
    public String mime;
    public long size;
    public String storageType;
    public String storage;
    public String sha256;
    public int status;
    @TableLogic(value = "null", delval = "CURRENT_TIMESTAMP(6)")
    @TableField(typeHandler = UtcInstantTypeHandler.class) public Instant deletedAt;
    public String uploadStatus;
    public String createBy;
    public String updateBy;
    @TableField(typeHandler = UtcInstantTypeHandler.class) public Instant createAt;
    @TableField(typeHandler = UtcInstantTypeHandler.class) public Instant updateAt;
}
