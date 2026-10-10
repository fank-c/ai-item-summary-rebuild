package com.item.aisummary.application.user.result;

import com.item.aisummary.domain.user.model.User;
import java.time.Instant;

/** 应用对外用户资料，不携带密码摘要或删除时间。 */
public record UserProfile(
        /** 用户 ID。 */
        String id,
        /** 用户展示名称。 */
        String name,
        /** 用户邮箱。 */
        String email,
        /** 账户业务状态，不等同于删除状态。 */
        int status,
        /** 用户创建时间。 */
        Instant createAt,
        /** 用户最后更新时间。 */
        Instant updateAt) {

    /**
     * 显式选择公开字段转换为对外公开的用户资料，避免跨层传递包含密码摘要或删除时间的领域快照。
     *
     * @param user 领域用户实体快照
     * @return 不含敏感信息的对外资料对象
     */
    public static UserProfile from(User user) {
        return new UserProfile(
                user.id(),
                user.name(),
                user.email(),
                user.status(),
                user.createAt(),
                user.updateAt());
    }
}
