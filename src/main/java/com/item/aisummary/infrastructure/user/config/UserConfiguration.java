package com.item.aisummary.infrastructure.user.config;

import com.item.aisummary.application.user.port.PasswordHasher;
import com.item.aisummary.application.user.port.UserRepository;
import com.item.aisummary.application.user.service.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

/** 在基础设施边界装配用户用例与其外部能力。 */
@Configuration(proxyBeanMethods = false)
public class UserConfiguration {
    @Bean
    UserService userService(UserRepository repository, PasswordHasher hasher, Clock clock) {
        return new UserService(repository, hasher, clock);
    }
}
