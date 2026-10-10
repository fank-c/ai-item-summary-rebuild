package com.item.aisummary.infrastructure.auth.web;

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

    /**
     * 在处理请求前注入模拟的用户身份（跳过 OPTIONS 请求）。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler 目标处理器
     * @return 恒为 true，继续后续拦截器或控制器处理
     */
    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"OPTIONS".equalsIgnoreCase(request.getMethod())) {
            LoginUserContext.set(request, currentUser);
        }
        return true;
    }

    /**
     * 在请求处理完成（无论成功还是异常）后清理注入的用户上下文。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler 目标处理器
     * @param exception 处理期间发生的异常（若有）
     */
    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        // 请求成功或抛异常都清理；身份不跨请求保存，也不写入 Session。
        LoginUserContext.clear(request);
    }
}
