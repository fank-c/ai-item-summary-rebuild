package com.item.aisummary.infrastructure.user.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** 仅负责 user 表映射；公开字段供 MyBatis 反射访问，不作为 HTTP 对象。 */
@TableName(value = "`user`", autoResultMap = true)
public final class UserEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    public String id;
    public String name;
    public String email;
    public String password;
    public int status;
    @TableLogic(value = "null", delval = "CURRENT_TIMESTAMP(6)")
    @TableField(value = "deleted_at", typeHandler = UtcInstantTypeHandler.class)
    public Instant deletedAt;
    @TableField(value = "create_at", typeHandler = UtcInstantTypeHandler.class)
    public Instant createAt;
    @TableField(value = "update_at", typeHandler = UtcInstantTypeHandler.class)
    public Instant updateAt;
}
