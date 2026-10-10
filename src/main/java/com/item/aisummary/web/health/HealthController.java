package com.item.aisummary.web.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供进程存活状态，不检查数据库或对象存储可用性。 */
@RestController
@RequestMapping("/api")
public class HealthController {

    /**
     * 查询当前应用进程存活状态。
     *
     * <p>仅表示当前 Spring Boot 进程在线并可响应 HTTP 请求，不探测外部数据库或存储可用性。
     *
     * @return 包含固定状态 "UP" 的健康状态响应
     */
    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP");
    }

    public record HealthResponse(
            /** 当前应用进程状态；正常响应固定为 UP。 */
            String status) {}
}
