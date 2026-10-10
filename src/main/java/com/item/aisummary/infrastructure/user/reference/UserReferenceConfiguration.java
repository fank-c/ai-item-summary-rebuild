package com.item.aisummary.infrastructure.user.reference;

import com.item.aisummary.application.user.reference.UserReferenceService;
import com.item.aisummary.application.user.service.UserService;
import com.item.aisummary.infrastructure.auth.config.MockAuthenticationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 根据身份模拟开关装配唯一的用户引用实现。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MockAuthenticationProperties.class)
public class UserReferenceConfiguration {
    /**
     * 在启用模拟身份时注册固定模拟用户引用实现。
     *
     * @param properties 模拟身份配置属性
     * @return 模拟用户引用服务
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "app.auth.mock",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    UserReferenceService mockUserReferenceService(MockAuthenticationProperties properties) {
        return new MockUserReferenceService(properties);
    }

    /**
     * 在禁用模拟身份时注册基于数据库持久化查询的用户引用实现。
     *
     * @param service 用户应用服务
     * @return 数据库用户引用服务
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.auth.mock", name = "enabled", havingValue = "false")
    UserReferenceService databaseUserReferenceService(UserService service) {
        return new DatabaseUserReferenceService(service);
    }
}
