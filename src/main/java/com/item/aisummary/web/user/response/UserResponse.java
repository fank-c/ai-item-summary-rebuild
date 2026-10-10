package com.item.aisummary.web.user.response;

import com.item.aisummary.application.user.result.UserProfile;
import java.time.Instant;

/** 固定六字段的 HTTP 响应，不包含密码摘要和删除时间。 */
public record UserResponse(
        /** 用户资源 ID。 */
        String id,
        /** 用户展示名称。 */
        String name,
        /** 用户邮箱。 */
        String email,
        /** 账户业务状态。 */
        int status,
        /** 用户创建时间。 */
        Instant createAt,
        /** 用户最后更新时间。 */
        Instant updateAt) {

    /**
     * 将应用层公开资料对象转换为 HTTP 响应对象。
     *
     * @param profile 应用层返回的用户资料
     * @return 映射后的 HTTP 响应记录
     */
    public static UserResponse from(UserProfile profile) {
        return new UserResponse(
                profile.id(),
                profile.name(),
                profile.email(),
                profile.status(),
                profile.createAt(),
                profile.updateAt());
    }
}
