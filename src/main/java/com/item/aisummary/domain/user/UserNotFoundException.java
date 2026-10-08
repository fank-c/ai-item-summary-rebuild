package com.item.aisummary.domain.user;

/** 不存在或已逻辑删除的用户均不可作为可用用户返回。 */
public final class UserNotFoundException extends RuntimeException {
    public UserNotFoundException() {
        super("用户不存在");
    }
}
