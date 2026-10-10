package com.item.aisummary.infrastructure.user.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.item.aisummary.application.user.port.UserRepository;
import com.item.aisummary.domain.user.model.User;
import com.item.aisummary.infrastructure.persistence.typehandler.UtcInstantTypeHandler;
import com.item.aisummary.infrastructure.user.persistence.entity.UserEntity;
import com.item.aisummary.infrastructure.user.persistence.mapper.UserMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 将应用仓储合同适配为 MyBatis-Plus 操作，封装实体映射和逻辑删除条件。 */
@Repository
public class MyBatisUserRepository implements UserRepository {
    private final UserMapper mapper;

    public MyBatisUserRepository(UserMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 插入用户实体到数据库，并返回包含业务标识的领域模型。
     *
     * @param user 用户领域实体
     * @return 插入成功后的用户领域实体
     */
    @Override
    public User insert(User user) {
        UserEntity entity = toEntity(user);
        if (mapper.insert(entity) != 1) {
            throw new IllegalStateException("用户插入结果不符合预期");
        }
        return toUser(entity);
    }

    /**
     * 根据主键 ID 查询用户，自动带上未删除条件过滤。
     *
     * @param id 用户唯一标识
     * @return 包含领域实体的 Optional，若不存在或已逻辑删除则为空
     */
    @Override
    public Optional<User> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MyBatisUserRepository::toUser);
    }

    /**
     * 查询所有未被逻辑删除的用户实体。
     *
     * @return 未删除用户领域实体列表
     */
    @Override
    public List<User> findAll() {
        return mapper.selectList(null).stream().map(MyBatisUserRepository::toUser).toList();
    }

    /**
     * 更新未删除用户的指定字段。
     *
     * <p>显式构造更新包装器，仅更新 name、email、update_at。
     * 若 passwordChanged 为 true，则追加 password 列；为 false 时省略 password 列以避免覆盖并发更新。
     *
     * @param user 包含更新信息的用户实体
     * @param passwordChanged 是否变更了密码
     * @return 数据库受影响行数
     */
    @Override
    public int update(User user, boolean passwordChanged) {
        // 显式列集合避免把旧快照中的摘要、状态或创建时间一并写回。
        var update =
                new UpdateWrapper<UserEntity>()
                        .eq("id", user.id())
                        .isNull("deleted_at")
                        .set("name", user.name())
                        .set("email", user.email())
                        .set(
                                "update_at",
                                user.updateAt(),
                                "typeHandler=" + UtcInstantTypeHandler.class.getName());

        // 未改密码时完全省略 password 列，保留数据库当前摘要。
        if (passwordChanged) {
            update.set("password", user.passwordHash());
        }
        return mapper.update(null, update);
    }

    /**
     * 执行逻辑删除，将 deleted_at 设置为数据库当前的 UTC 时间。
     *
     * @param id 用户唯一标识
     * @param updateAt 审计更新时间
     * @return 数据库受影响行数
     */
    @Override
    public int softDelete(String id, Instant updateAt) {
        // 删除时间来自数据库；status 和个人资料均不在更新集合中。
        var update =
                new UpdateWrapper<UserEntity>()
                        .eq("id", id)
                        .isNull("deleted_at")
                        .setSql("deleted_at = CURRENT_TIMESTAMP(6)")
                        .set(
                                "update_at",
                                updateAt,
                                "typeHandler=" + UtcInstantTypeHandler.class.getName());
        return mapper.update(null, update);
    }

    /** 将领域 User 转换为持久化实体 UserEntity。 */
    private static UserEntity toEntity(User user) {
        UserEntity entity = new UserEntity();
        entity.id = user.id();
        entity.name = user.name();
        entity.email = user.email();
        entity.password = user.passwordHash();
        entity.status = user.status();
        entity.deletedAt = user.deletedAt();
        entity.createAt = user.createAt();
        entity.updateAt = user.updateAt();
        return entity;
    }

    /** 将持久化实体 UserEntity 转换为领域 User。 */
    private static User toUser(UserEntity entity) {
        return new User(
                entity.id,
                entity.name,
                entity.email,
                entity.password,
                entity.status,
                entity.deletedAt,
                entity.createAt,
                entity.updateAt);
    }
}
