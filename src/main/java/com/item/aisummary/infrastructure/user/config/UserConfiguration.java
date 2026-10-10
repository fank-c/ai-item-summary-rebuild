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
    /**
     * 装配用户核心应用服务。
     *
     * @param repository 用户持久化仓储接口
     * @param hasher 密码哈希摘要接口
     * @param clock 统一时钟
     * @return 用户应用服务实例
     */
    @Bean
    UserService userService(UserRepository repository, PasswordHasher hasher, Clock clock) {
        return new UserService(repository, hasher, clock);
    }
}
