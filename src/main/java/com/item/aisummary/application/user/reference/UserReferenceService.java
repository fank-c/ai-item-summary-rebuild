package com.item.aisummary.application.user.reference;

/** 确认资源归属引用，不验证调用方凭据，也不按账户 status 决定可用性。 */
public interface UserReferenceService {
    UserReference requireExisting(String userId);
}
