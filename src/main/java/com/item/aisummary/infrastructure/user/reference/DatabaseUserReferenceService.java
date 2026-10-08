package com.item.aisummary.infrastructure.user.reference;

import com.item.aisummary.application.user.UserProfile;
import com.item.aisummary.application.user.UserService;
import com.item.aisummary.application.user.reference.UserReference;
import com.item.aisummary.application.user.reference.UserReferenceService;

/** 复用未删除用户查询，确认引用而不解释 status 为启停状态。 */
public final class DatabaseUserReferenceService implements UserReferenceService {
    private final UserService service;

    public DatabaseUserReferenceService(UserService service) {
        this.service = service;
    }

    @Override
    public UserReference requireExisting(String userId) {
        UserProfile profile = service.findById(userId);
        return new UserReference(profile.id(), profile.name());
    }
}
