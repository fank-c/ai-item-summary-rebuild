package com.item.aisummary.application.auth;

/** 使用需要当前身份的能力时，请求没有获得身份。 */
public final class AuthenticationRequiredException extends RuntimeException {

    /** 构造需要当前用户身份的业务异常。 */
    public AuthenticationRequiredException() {
        super("需要当前用户身份");
    }
}
