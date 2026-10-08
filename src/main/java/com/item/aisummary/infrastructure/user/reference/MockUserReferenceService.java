package com.item.aisummary.infrastructure.user.reference;

import com.item.aisummary.application.user.reference.UserReference;
import com.item.aisummary.application.user.reference.UserReferenceService;
import com.item.aisummary.infrastructure.auth.MockAuthenticationProperties;

/** 固定用户引用，不访问用户表，也不把模拟用户持久化。 */
public final class MockUserReferenceService implements UserReferenceService {
    private final UserReference reference;

    public MockUserReferenceService(MockAuthenticationProperties properties) {
        reference = new UserReference(properties.userId(), properties.username());
    }

    @Override
    public UserReference requireExisting(String userId) {
        if (userId == null || !reference.id().equals(userId.trim())) {
            throw new IllegalArgumentException("模拟用户不存在");
        }
        return reference;
    }
}
