package com.item.aisummary.application.file.policy;

import java.time.Duration;
import java.util.Set;

/** 应用所需的文件约束，不依赖 Spring 配置类型。 */
public record FilePolicy(
        /** 单个文件允许的实际字节数上限。 */
        long maxBytes,
        /** 文件名扩展名白名单，比较时忽略大小写。 */
        Set<String> allowedExtensions,
        /** 新对象 Key 的公共文件目录；厂商专用目录存在时叠加在此前。 */
        String keyPrefix,
        /** 下载临时凭证的有效期。 */
        Duration downloadExpiration) {
    public FilePolicy {
        allowedExtensions = Set.copyOf(allowedExtensions);
    }
}
