package com.item.aisummary.application.user.port;

import com.item.aisummary.domain.user.model.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 用户持久化能力；常规查询只返回未删除用户。 */
public interface UserRepository {
    /**
     * 插入一条用户记录，并返回带有生成 ID 的用户实体。
     *
     * @param user 待插入的用户实体
     * @return 插入成功后的用户实体
     * @throws IllegalStateException 写入受影响行数不为 1
     */
    User insert(User user);

    /**
     * 根据用户 ID 查询单个用户。
     *
     * <p>仅查询未被逻辑删除的用户。
     *
     * @param id 用户唯一标识
     * @return 包含用户实体的 Optional，若不存在或已删除则返回空
     */
    Optional<User> findById(String id);

    /**
     * 查询所有未被逻辑删除的用户列表。
     *
     * @return 未删除用户列表
     */
    List<User> findAll();

    /**
     * 更新未删除用户的资料。
     *
     * <p>仅更新 name、email、update_at，以及明确要求修改的 password。不写入 id、status、create_at、deleted_at。
     * 当 passwordChanged 为 false 时，SQL 语句必须完全省略 password 列，以避免覆盖并发更新的摘要。
     *
     * @param user 包含更新信息的用户实体
     * @param passwordChanged 是否明确修改了密码
     * @return 数据库受影响行数
     */
    int update(User user, boolean passwordChanged);

    /**
     * 逻辑删除指定用户。
     *
     * <p>将 deleted_at 设置为数据库当前时间，记录更新时间 update_at，不修改账户 status。
     *
     * @param id 用户唯一标识
     * @param updateAt 更新时间戳
     * @return 数据库受影响行数
     */
    int softDelete(String id, Instant updateAt);
}
