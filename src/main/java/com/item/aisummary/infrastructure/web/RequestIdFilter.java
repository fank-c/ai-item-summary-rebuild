package com.item.aisummary.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 为 HTTP 请求和错误响应提供关联 ID，不承担认证职责。 */
@Component
public final class RequestIdFilter extends OncePerRequestFilter {
    /** HTTP 请求和响应使用的关联 ID 请求头。 */
    public static final String HEADER_NAME = "X-Request-Id";

    /** 请求属性中的关联 ID 键；错误处理器从此处读取。 */
    public static final String REQUEST_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /**
     * 过滤 HTTP 请求，提取或生成请求关联 ID，并写入请求属性与响应头中。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param chain 过滤器链
     * @throws ServletException Servlet 异常
     * @throws IOException I/O 异常
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String candidate = request.getHeader(HEADER_NAME);
        String requestId = resolveRequestId(candidate);
        request.setAttribute(REQUEST_ATTRIBUTE, requestId);
        response.setHeader(HEADER_NAME, requestId);
        chain.doFilter(request, response);
    }

    /**
     * 解析请求标识：若请求头中包含符合安全正则的 ID 则透传，否则生成新的随机 UUID。
     *
     * @param candidate 候选请求头标识
     * @return 解析或生成的安全请求 ID
     */
    private static String resolveRequestId(String candidate) {
        return candidate != null && SAFE_ID.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }
}
