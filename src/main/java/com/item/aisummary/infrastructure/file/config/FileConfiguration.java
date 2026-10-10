package com.item.aisummary.infrastructure.file.config;

import com.item.aisummary.application.file.policy.FilePolicy;
import com.item.aisummary.application.file.port.FileRepository;
import com.item.aisummary.application.file.port.ObjectStorageClient;
import com.item.aisummary.application.file.port.ObjectStorageRouter;
import com.item.aisummary.application.file.service.FileService;
import com.item.aisummary.infrastructure.file.storage.AliyunOssObjectStorageClient;
import com.item.aisummary.infrastructure.file.storage.ConfiguredStorageRouter;
import com.item.aisummary.infrastructure.file.storage.MinioObjectStorageClient;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 应用与基础设施装配，存储默认关闭，不依赖模型凭据。 按开关创建以厂商名命名的 SDK Bean，路由据此匹配；容器退出时调用 close。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FileProperties.class)
public class FileConfiguration {
    /**
     * 按配置启用 MinIO 存储客户端 Bean，并在容器销毁时关闭网络连接。
     *
     * @param properties 文件存储配置
     * @return MinIO 对象存储适配客户端
     */
    @Bean(name = "minio", destroyMethod = "close")
    @ConditionalOnProperty(
            prefix = "app.file.storage.minio",
            name = "enabled",
            havingValue = "true")
    ObjectStorageClient minio(FileProperties properties) {
        return MinioObjectStorageClient.create(properties.storage());
    }

    /**
     * 按配置启用阿里云 OSS 存储客户端 Bean，并在容器销毁时关闭网络连接。
     *
     * @param properties 文件存储配置
     * @return 阿里云 OSS 对象存储适配客户端
     */
    @Bean(name = "aliyun", destroyMethod = "close")
    @ConditionalOnProperty(
            prefix = "app.file.storage.aliyun",
            name = "enabled",
            havingValue = "true")
    ObjectStorageClient aliyun(FileProperties properties) {
        return AliyunOssObjectStorageClient.create(properties.storage());
    }

    /** 在基础设施边界把 Spring 配置类型转换为应用值对象。 */
    @Bean
    FilePolicy filePolicy(FileProperties properties) {
        return new FilePolicy(
                properties.upload().maxSize().toBytes(),
                properties.upload().allowedExtensions(),
                properties.storage().keyPrefix(),
                properties.storage().downloadUrlExpiration());
    }

    /** 将已启用的厂商客户端交给显式类型路由器。 */
    @Bean
    ObjectStorageRouter objectStorageRouter(
            FileProperties properties, Map<String, ObjectStorageClient> clients) {
        return new ConfiguredStorageRouter(properties, clients);
    }

    /** 组装只依赖应用层端口的文件服务。 */
    @Bean
    FileService fileService(
            FileRepository repository, ObjectStorageRouter storage, FilePolicy policy) {
        return new FileService(repository, storage, policy);
    }
}
