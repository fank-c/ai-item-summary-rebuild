package com.item.aisummary.application.user;

import com.item.aisummary.domain.user.User;
import java.time.Instant;

/** 应用对外用户资料，不携带密码摘要或删除时间。 */
public record UserProfile(String id, String name, String email, int status, Instant createAt, Instant updateAt) {
    public static UserProfile from(User user) {
        return new UserProfile(user.id(), user.name(), user.email(), user.status(), user.createAt(), user.updateAt());
    }
}
