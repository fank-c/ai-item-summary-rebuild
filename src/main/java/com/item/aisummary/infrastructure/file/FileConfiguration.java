package com.item.aisummary.infrastructure.file;

import com.item.aisummary.application.file.FilePolicy;
import com.item.aisummary.application.file.FileService;
import com.item.aisummary.application.file.port.*;
import com.item.aisummary.infrastructure.file.storage.ConfiguredStorageRouter;
import com.item.aisummary.infrastructure.file.storage.MinioObjectStorageClient;
import com.item.aisummary.infrastructure.file.storage.AliyunOssObjectStorageClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Map;

/** 应用与基础设施装配，存储默认关闭，不依赖模型凭据。 */
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
