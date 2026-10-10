package com.item.aisummary.web.user.response;

import com.item.aisummary.application.user.result.UserProfile;
import java.time.Instant;

/** 固定六字段的 HTTP 响应。 */
public record UserResponse(String id, String name, String email, int status, Instant createAt, Instant updateAt) {
    public static UserResponse from(UserProfile profile) {
        return new UserResponse(profile.id(), profile.name(), profile.email(), profile.status(), profile.createAt(), profile.updateAt());
    }
}
