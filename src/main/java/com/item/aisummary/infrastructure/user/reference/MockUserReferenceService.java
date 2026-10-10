package com.item.aisummary.infrastructure.user.reference;

import com.item.aisummary.application.user.reference.UserReference;
import com.item.aisummary.application.user.reference.UserReferenceService;
import com.item.aisummary.infrastructure.auth.config.MockAuthenticationProperties;

/** 固定用户引用，不访问用户表，也不把模拟用户持久化。 */
public final class MockUserReferenceService implements UserReferenceService {
    private final UserReference reference;

    public MockUserReferenceService(MockAuthenticationProperties properties) {
        reference = new UserReference(properties.userId(), properties.username());
    }

    @Override
    public UserReference requireExisting(String userId) {
        // 模拟身份不是数据库账户；即使用户表有同名 ID，也不放宽配置 ID 的匹配。
        if (userId == null || !reference.id().equals(userId.trim())) {
            throw new IllegalArgumentException("模拟用户不存在");
        }
        return reference;
    }
}
