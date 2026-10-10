package com.item.aisummary.infrastructure.file.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.item.aisummary.infrastructure.persistence.typehandler.UtcInstantTypeHandler;
import java.time.Instant;

/** file_asset 表映射；领域类型不依赖 MyBatis，直传过期列保留为空。 */
@TableName(value = "file_asset", autoResultMap = true)
public final class FileEntity {
    /** 文件 ID；由应用层生成，关联文件元数据而不是文件正文。 */
    @TableId(type = IdType.INPUT)
    public String id;

    /** 用户上传时提供的原始展示名称。 */
    public String originName;

    /** 规范化后的 MIME 类型；不代表服务端已识别文件实际格式。 */
    public String mime;

    /** 第一次读取文件得到的实际字节数。 */
    public long size;

    /** 对象存储路由类型，例如 minio 或 aliyun。 */
    public String storageType;

    /** 对象存储中的 Key；历史记录按原值使用。 */
    public String storage;

    /** 第一次读取文件得到的 SHA-256 摘要。 */
    public String sha256;

    /** 文件业务状态；可处理条件还包括未删除和上传已完成。 */
    public int status;

    /** 逻辑删除时间；为 null 表示未删除，删除时由数据库生成时间。 */
    @TableLogic(value = "null", delval = "CURRENT_TIMESTAMP(6)")
    @TableField(typeHandler = UtcInstantTypeHandler.class)
    public Instant deletedAt;

    /** 上传生命周期状态；当前成功上传记录使用 completed。 */
    public String uploadStatus;

    /** 创建文件记录的模拟或真实用户 ID；不建立数据库外键。 */
    public String createBy;

    /** 最后更新文件记录的用户 ID；不建立数据库外键。 */
    public String updateBy;

    /** 创建时间；通过 UTC 类型处理器映射数据库的 DATETIME(6)。 */
    @TableField(typeHandler = UtcInstantTypeHandler.class)
    public Instant createAt;

    /** 最后更新时间；通过 UTC 类型处理器映射数据库的 DATETIME(6)。 */
    @TableField(typeHandler = UtcInstantTypeHandler.class)
    public Instant updateAt;
}
