package com.item.aisummary.infrastructure.file.config;

import com.item.aisummary.application.file.policy.FilePolicy;
import com.item.aisummary.application.file.port.FileRepository;
import com.item.aisummary.application.file.port.ObjectStorageClient;
import com.item.aisummary.application.file.port.ObjectStorageRouter;
import com.item.aisummary.application.file.service.FileService;
import com.item.aisummary.infrastructure.file.storage.AliyunOssObjectStorageClient;
import com.item.aisummary.infrastructure.file.storage.ConfiguredStorageRouter;
import com.item.aisummary.infrastructure.file.storage.MinioObjectStorageClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Map;

/**
 * 应用与基础设施装配，存储默认关闭，不依赖模型凭据。
 * 按开关创建以厂商名命名的 SDK Bean，路由据此匹配；容器退出时调用 close。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FileProperties.class)
public class FileConfiguration {
    @Bean(name = "minio", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.file.storage.minio", name = "enabled", havingValue = "true")
    ObjectStorageClient minio(FileProperties properties) {
        return MinioObjectStorageClient.create(properties.storage());
    }
    @Bean(name = "aliyun", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.file.storage.aliyun", name = "enabled", havingValue = "true")
    ObjectStorageClient aliyun(FileProperties properties) {
        return AliyunOssObjectStorageClient.create(properties.storage());
    }
    // 在基础设施边界把 Spring 配置类型转成应用值对象，避免用例依赖配置绑定。
    @Bean FilePolicy filePolicy(FileProperties properties) {
        return new FilePolicy(properties.upload().maxSize().toBytes(), properties.upload().allowedExtensions(),
            properties.storage().keyPrefix(), properties.storage().downloadUrlExpiration());
    }
    @Bean ObjectStorageRouter objectStorageRouter(FileProperties properties, Map<String, ObjectStorageClient> clients) {
        return new ConfiguredStorageRouter(properties, clients);
    }
    @Bean FileService fileService(FileRepository repository, ObjectStorageRouter storage, FilePolicy policy) {
        return new FileService(repository, storage, policy);
    }
}
