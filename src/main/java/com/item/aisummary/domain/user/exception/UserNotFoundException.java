package com.item.aisummary.domain.user.exception;

/** 不存在或已逻辑删除的用户均不可作为可用用户返回。 */
public final class UserNotFoundException extends RuntimeException {

    /** 构造用户不存在或已被逻辑删除时的业务异常。 */
    public UserNotFoundException() {
        super("用户不存在");
    }
}
