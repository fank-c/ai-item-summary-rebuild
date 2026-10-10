package com.item.aisummary.infrastructure.user.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.item.aisummary.infrastructure.persistence.typehandler.UtcInstantTypeHandler;
import java.time.Instant;

/** 仅负责 user 表映射；公开字段供 MyBatis 反射访问，不作为 HTTP 对象。 */
@TableName(value = "`user`", autoResultMap = true)
public final class UserEntity {
    /** 用户 ID；由应用层提供，最多 32 个字符。 */
    @TableId(type = IdType.ASSIGN_UUID)
    public String id;

    /** 用户展示名称；写入前由应用服务裁剪普通文本。 */
    public String name;

    /** 用户邮箱；保存标准化后的普通文本，不参与密码摘要。 */
    public String email;

    /** BCrypt 密码摘要；禁止保存或返回明文密码。 */
    public String password;

    /** 账户业务状态；账户状态与逻辑删除状态相互独立。 */
    public int status;

    /** 逻辑删除时间；为 null 表示未删除，删除时由数据库生成 UTC 时间。 */
    @TableLogic(value = "null", delval = "CURRENT_TIMESTAMP(6)")
    @TableField(value = "deleted_at", typeHandler = UtcInstantTypeHandler.class)
    public Instant deletedAt;

    /** 创建时间；通过 UTC 类型处理器映射数据库的 DATETIME(6)。 */
    @TableField(value = "create_at", typeHandler = UtcInstantTypeHandler.class)
    public Instant createAt;

    /** 最后更新时间；通过 UTC 类型处理器映射数据库的 DATETIME(6)。 */
    @TableField(value = "update_at", typeHandler = UtcInstantTypeHandler.class)
    public Instant updateAt;
}
