package com.item.aisummary.infrastructure.user.reference;

import com.item.aisummary.application.user.reference.UserReference;
import com.item.aisummary.application.user.reference.UserReferenceService;
import com.item.aisummary.infrastructure.auth.config.MockAuthenticationProperties;

/** 固定用户引用，不访问用户表，也不把模拟用户持久化。 */
public final class MockUserReferenceService implements UserReferenceService {
    private final UserReference reference;

    /**
     * 根据模拟身份配置创建固定用户引用实例。
     *
     * @param properties 模拟身份配置属性
     */
    public MockUserReferenceService(MockAuthenticationProperties properties) {
        reference = new UserReference(properties.userId(), properties.username());
    }

    /**
     * 校验请求的 ID 是否与当前配置的模拟用户一致。
     *
     * <p>在模拟模式下，模拟用户不是数据库持久化账户，仅允许匹配配置中预设的模拟用户 ID，不查询数据库。
     *
     * @param userId 待校验的用户 ID
     * @return 配置的模拟用户引用
     * @throws IllegalArgumentException 若传入的用户 ID 为空或与模拟用户 ID 不匹配
     */
    @Override
    public UserReference requireExisting(String userId) {
        // 模拟身份不是数据库账户，不因用户表存在同名 ID 而放宽配置匹配。
        if (userId == null || !reference.id().equals(userId.trim())) {
            throw new IllegalArgumentException("模拟用户不存在");
        }
        return reference;
    }
}
