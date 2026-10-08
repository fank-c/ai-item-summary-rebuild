package com.item.aisummary.infrastructure.auth;

import com.item.aisummary.application.auth.ActorUserIdRules;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 模拟身份配置，与数据库账户和真实凭据无关。 */
@ConfigurationProperties("app.auth.mock")
public record MockAuthenticationProperties(@DefaultValue("true") boolean enabled,
                                            @DefaultValue("mock-user") String userId,
                                            @DefaultValue("mock-user") String username) {
    public MockAuthenticationProperties {
        userId = userId == null ? null : userId.trim();
        username = username == null ? null : username.trim();
        if (enabled && (userId == null || userId.isBlank() || username == null || username.isBlank())) {
            throw new IllegalArgumentException("模拟用户 ID 和展示名称不能为空");
        }
        if (enabled) {
            ActorUserIdRules.requireValid(userId);
        }
    }
}
