package com.item.aisummary.infrastructure.user.reference;

import com.item.aisummary.application.user.reference.UserReference;
import com.item.aisummary.application.user.reference.UserReferenceService;
import com.item.aisummary.application.user.result.UserProfile;
import com.item.aisummary.application.user.service.UserService;

/** 复用未删除用户查询，确认引用而不解释 status 为启停状态。 */
public final class DatabaseUserReferenceService implements UserReferenceService {
    private final UserService service;

    /**
     * 创建基于数据库查询的用户引用服务。
     *
     * @param service 用户应用服务
     */
    public DatabaseUserReferenceService(UserService service) {
        this.service = service;
    }

    /**
     * 查询未删除的数据库用户并提取跨模块用户引用。
     *
     * <p>仅验证用户存在且未被逻辑删除，不将用户的 status 状态解释为启停拦截条件。
     *
     * @param userId 目标用户业务 ID
     * @return 包含用户 ID 与用户名的最小引用对象
     * @throws com.item.aisummary.domain.user.exception.UserNotFoundException 若用户不存在或已被逻辑删除
     */
    @Override
    public UserReference requireExisting(String userId) {
        UserProfile profile = service.findById(userId);
        return new UserReference(profile.id(), profile.name());
    }
}
