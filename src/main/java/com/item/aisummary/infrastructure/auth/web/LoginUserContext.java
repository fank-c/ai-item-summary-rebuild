package com.item.aisummary.infrastructure.auth.web;

import com.item.aisummary.application.auth.AuthenticationRequiredException;
import com.item.aisummary.application.auth.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * 身份仅保存在请求属性；调用方把所需用户信息显式传入应用用例。
 * 不使用 ThreadLocal 或 Session，后台任务不能从此处恢复请求身份。
 */
public final class LoginUserContext {
    public static final String REQUEST_ATTRIBUTE = CurrentUser.class.getName();

    private LoginUserContext() { }

    public static Optional<CurrentUser> get(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(REQUEST_ATTRIBUTE);
        return value instanceof CurrentUser user ? Optional.of(user) : Optional.empty();
    }

    public static CurrentUser require(HttpServletRequest request) {
        return get(request).orElseThrow(AuthenticationRequiredException::new);
    }

    static void set(HttpServletRequest request, CurrentUser user) {
        request.setAttribute(REQUEST_ATTRIBUTE, user);
    }

    static void clear(HttpServletRequest request) {
        if (request != null) {
            request.removeAttribute(REQUEST_ATTRIBUTE);
        }
    }
}
