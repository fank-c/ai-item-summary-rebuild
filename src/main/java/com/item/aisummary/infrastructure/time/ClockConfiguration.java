package com.item.aisummary.infrastructure.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

/** 为需要应用时间的模块提供共享 UTC 时钟。 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {
    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }
}
