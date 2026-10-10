package com.item.aisummary.infrastructure.user.persistence.repository;

import com.item.aisummary.application.user.port.UserRepository;
import com.item.aisummary.domain.user.model.User;
import com.item.aisummary.infrastructure.persistence.typehandler.UtcInstantTypeHandler;
import com.item.aisummary.infrastructure.user.persistence.entity.UserEntity;
import com.item.aisummary.infrastructure.user.persistence.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 将应用仓储合同适配为 MyBatis-Plus 操作，封装数据库实体和条件逻辑删除。 */
@Repository
public class MyBatisUserRepository implements UserRepository {
    private final UserMapper mapper;

    public MyBatisUserRepository(UserMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public User insert(User user) {
        UserEntity entity = toEntity(user);
        if (mapper.insert(entity) != 1) {
            throw new IllegalStateException("用户插入结果不符合预期");
        }
        return toUser(entity);
    }

    @Override
    public Optional<User> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MyBatisUserRepository::toUser);
    }

    @Override
    public List<User> findAll() {
        return mapper.selectList(null).stream().map(MyBatisUserRepository::toUser).toList();
    }

    @Override
    public int update(User user, boolean passwordChanged) {
        // 显式列集合避免 updateById 把旧快照的摘要、状态或创建时间一并写回。
        UpdateWrapper<UserEntity> update = new UpdateWrapper<UserEntity>()
            .eq("id", user.id()).isNull("deleted_at")
            .set("name", user.name())
            .set("email", user.email())
            .set("update_at", user.updateAt(), "typeHandler=" + UtcInstantTypeHandler.class.getName());
        // 未改密码时完全省略该列，保留数据库当前摘要；显式改密码仍没有版本冲突检查。
        if (passwordChanged) {
            update.set("password", user.passwordHash());
        }
        return mapper.update(null, update);
    }

    @Override
    public int softDelete(String id, Instant updateAt) {
        // 删除时间来自数据库；status 和个人资料均不在更新集合中。
        UpdateWrapper<UserEntity> update = new UpdateWrapper<UserEntity>()
            .eq("id", id).isNull("deleted_at")
            .setSql("deleted_at = CURRENT_TIMESTAMP(6)")
            .set("update_at", updateAt, "typeHandler=" + UtcInstantTypeHandler.class.getName());
        return mapper.update(null, update);
    }

    private static UserEntity toEntity(User user) {
        UserEntity entity = new UserEntity();
        entity.id = user.id(); entity.name = user.name(); entity.email = user.email();
        entity.password = user.passwordHash(); entity.status = user.status(); entity.deletedAt = user.deletedAt();
        entity.createAt = user.createAt(); entity.updateAt = user.updateAt();
        return entity;
    }

    private static User toUser(UserEntity entity) {
        return new User(entity.id, entity.name, entity.email, entity.password, entity.status,
            entity.deletedAt, entity.createAt, entity.updateAt);
    }
}
