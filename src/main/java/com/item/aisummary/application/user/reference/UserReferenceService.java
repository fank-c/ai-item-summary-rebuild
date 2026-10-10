package com.item.aisummary.application.user.reference;

/** 确认资源归属引用，不验证调用方凭据，也不按账户 status 决定可用性。 */
public interface UserReferenceService {
    /** 返回最小用户引用；模拟实现只接受配置 ID，数据库实现只返回未删除用户。 */
    UserReference requireExisting(String userId);
}
