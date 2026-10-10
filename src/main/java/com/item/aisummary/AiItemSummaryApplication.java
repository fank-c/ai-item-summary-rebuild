package com.item.aisummary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot 启动入口；应用配置和基础设施 Bean 由框架自动装配。 */
@SpringBootApplication
public class AiItemSummaryApplication {

    /**
     * 应用程序启动主入口。
     *
     * @param args 命令行启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AiItemSummaryApplication.class, args);
    }
}
