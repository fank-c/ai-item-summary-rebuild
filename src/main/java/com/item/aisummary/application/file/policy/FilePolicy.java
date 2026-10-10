package com.item.aisummary.application.file.policy;

import java.time.Duration;
import java.util.Set;

/** 应用所需的文件约束，不依赖 Spring 配置类型。 */
public record FilePolicy(long maxBytes, Set<String> allowedExtensions, String keyPrefix, Duration downloadExpiration) {
    public FilePolicy {
        allowedExtensions = Set.copyOf(allowedExtensions);
    }
}
