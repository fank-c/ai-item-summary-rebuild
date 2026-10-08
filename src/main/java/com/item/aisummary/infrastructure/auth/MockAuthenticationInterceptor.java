package com.item.aisummary.infrastructure.auth;

import com.item.aisummary.application.auth.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** 固定身份注入，不解析凭据、不查询用户表、不执行权限判断。 */
public final class MockAuthenticationInterceptor implements HandlerInterceptor {
    private final CurrentUser currentUser;

    public MockAuthenticationInterceptor(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"OPTIONS".equalsIgnoreCase(request.getMethod())) {
            LoginUserContext.set(request, currentUser);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        LoginUserContext.clear(request);
    }
}
