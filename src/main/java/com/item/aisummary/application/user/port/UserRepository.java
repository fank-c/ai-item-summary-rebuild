package com.item.aisummary.application.user.port;

import com.item.aisummary.domain.user.model.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 用户持久化能力；常规查询只返回未删除用户。 */
public interface UserRepository {
    /** 插入一行并返回带生成 ID 的用户；写入行数异常时抛出运行时异常。 */
    User insert(User user);
    Optional<User> findById(String id);
    List<User> findAll();
    /**
     * 只更新未删除记录的 name、email、update_at，以及明确要求修改的 password。
     * 不写 id、status、create_at、deleted_at；返回行数供服务检查是否恰好写入一行。
     * passwordChanged=false 时 SQL 必须完全省略 password 列，不能写回快照中的旧摘要。
     */
    int update(User user, boolean passwordChanged);
    /** 删除时间由数据库生成，更新时间由调用方提供，不改变账户 status。 */
    int softDelete(String id, Instant updateAt);
}
