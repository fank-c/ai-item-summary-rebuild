package com.item.aisummary.infrastructure.auth.web;

import com.item.aisummary.application.auth.AuthenticationRequiredException;
import com.item.aisummary.application.auth.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** 身份仅保存在请求属性；调用方把所需用户信息显式传入应用用例。 不使用 ThreadLocal 或 Session，后台任务不能从此处恢复请求身份。 */
public final class LoginUserContext {
    /** 请求属性键；身份生命周期只覆盖当前 HTTP 请求。 */
    public static final String REQUEST_ATTRIBUTE = CurrentUser.class.getName();

    private LoginUserContext() {}

    /**
     * 读取当前 HTTP 请求绑定的最小用户身份。
     *
     * @param request 当前 HTTP 请求
     * @return 包含当前身份的 Optional，未绑定时为空
     */
    public static Optional<CurrentUser> get(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(REQUEST_ATTRIBUTE);
        return value instanceof CurrentUser user ? Optional.of(user) : Optional.empty();
    }

    /**
     * 获取当前 HTTP 请求绑定的用户身份，若不存在则抛出异常。
     *
     * @param request 当前 HTTP 请求
     * @return 当前用户身份对象
     * @throws AuthenticationRequiredException 请求中不存在有效身份
     */
    public static CurrentUser require(HttpServletRequest request) {
        return get(request).orElseThrow(AuthenticationRequiredException::new);
    }

    /** 将用户身份绑定到当前 HTTP 请求属性中。 */
    static void set(HttpServletRequest request, CurrentUser user) {
        request.setAttribute(REQUEST_ATTRIBUTE, user);
    }

    /** 清理当前 HTTP 请求属性中的用户身份。 */
    static void clear(HttpServletRequest request) {
        if (request != null) {
            request.removeAttribute(REQUEST_ATTRIBUTE);
        }
    }
}
