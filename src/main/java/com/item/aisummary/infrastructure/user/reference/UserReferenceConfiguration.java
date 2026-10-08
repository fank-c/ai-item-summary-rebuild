package com.item.aisummary.infrastructure.user.reference;

import com.item.aisummary.application.user.UserService;
import com.item.aisummary.application.user.reference.UserReferenceService;
import com.item.aisummary.infrastructure.auth.MockAuthenticationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 根据身份模拟开关装配唯一的用户引用实现。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MockAuthenticationProperties.class)
public class UserReferenceConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "app.auth.mock", name = "enabled", havingValue = "true", matchIfMissing = true)
    UserReferenceService mockUserReferenceService(MockAuthenticationProperties properties) {
        return new MockUserReferenceService(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.auth.mock", name = "enabled", havingValue = "false")
    UserReferenceService databaseUserReferenceService(UserService service) {
        return new DatabaseUserReferenceService(service);
    }
}
