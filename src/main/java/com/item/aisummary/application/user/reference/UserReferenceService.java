package com.item.aisummary.application.user.reference;

/** 确认资源归属引用，不验证调用方凭据，也不按账户 status 决定可用性。 */
public interface UserReferenceService {
    /**
     * 确认并获取指定用户的最小引用对象。
     *
     * <p>模拟实现只接受配置的模拟用户 ID，数据库实现查询未被逻辑删除的用户。
     *
     * @param userId 待引用的用户 ID
     * @return 包含用户 ID 与名称的最小引用对象
     * @throws com.item.aisummary.domain.user.exception.UserNotFoundException 用户不存在或已被逻辑删除
     */
    UserReference requireExisting(String userId);
}
