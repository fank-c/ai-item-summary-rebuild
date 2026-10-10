package com.item.aisummary.infrastructure.auth.config;

import com.item.aisummary.application.auth.CurrentUser;
import com.item.aisummary.infrastructure.auth.web.MockAuthenticationInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 只对 /api/** 注册模拟身份，关闭开关时不提供隐含身份。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MockAuthenticationProperties.class)
@ConditionalOnProperty(
        prefix = "app.auth.mock",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MockAuthenticationConfiguration implements WebMvcConfigurer {
    private final MockAuthenticationInterceptor interceptor;

    public MockAuthenticationConfiguration(MockAuthenticationProperties properties) {
        interceptor =
                new MockAuthenticationInterceptor(
                        new CurrentUser(properties.userId(), properties.username()));
    }

    /**
     * 注册模拟身份拦截器至 Web 路由，仅拦截并对 {@code /api/**} 请求注入模拟用户。
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
