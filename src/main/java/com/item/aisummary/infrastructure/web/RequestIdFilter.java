package com.item.aisummary.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** 为 HTTP 请求和错误响应提供关联 ID，不承担认证职责。 */
@Component
public final class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Request-Id";
    public static final String REQUEST_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String candidate = request.getHeader(HEADER_NAME);
        String id = candidate != null && SAFE_ID.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ATTRIBUTE, id);
        response.setHeader(HEADER_NAME, id);
        chain.doFilter(request, response);
    }
}
