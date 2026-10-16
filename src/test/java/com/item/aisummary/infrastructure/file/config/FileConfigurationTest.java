package com.item.aisummary.infrastructure.file.config;

import com.item.aisummary.application.file.port.FileRepository;
import com.item.aisummary.application.file.port.ObjectStorageClient;
import com.item.aisummary.application.file.port.ObjectStorageRouter;
import com.item.aisummary.application.file.service.FileService;
import com.item.aisummary.domain.file.exception.FileValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 装配验证使用虚构 SDK 凭据，只构造客户端，不访问外网。 */
class FileConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(FileConfiguration.class).withBean(FileRepository.class, () -> mock(FileRepository.class));
    @Test void disabledStorageStartsButNeverFallsBack() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(FileService.class).doesNotHaveBean(ObjectStorageClient.class);
            assertThatThrownBy(() -> context.getBean(ObjectStorageRouter.class).resolve("minio"))
                .isInstanceOf(FileValidationException.class);
        });
    }
    @Test void enabledMinioUsesRawSdkAndRejectsOtherTypes() {
        runner.withPropertyValues("app.file.storage.minio.enabled=true", "app.file.storage.minio.endpoint=http://127.0.0.1:19000",
            "app.file.storage.minio.bucket=test-bucket", "app.file.storage.minio.access-key=test-access",
            "app.file.storage.minio.secret-key=test-secret")
            .run(context -> {
                assertThat(context).hasSingleBean(ObjectStorageClient.class);
                var router = context.getBean(ObjectStorageRouter.class);
                assertThat(router.resolve("minio").bucket()).isEqualTo("test-bucket");
                assertThatThrownBy(() -> router.resolve("aliyun")).isInstanceOf(FileValidationException.class);
            });
    }
    @Test void enabledAliyunUsesRawSdk() {
        runner.withPropertyValues("app.file.storage.aliyun.enabled=true", "app.file.storage.aliyun.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
            "app.file.storage.aliyun.bucket=test-bucket", "app.file.storage.aliyun.access-key-id=test-access",
            "app.file.storage.aliyun.access-key-secret=test-secret")
            .run(context -> {
                assertThat(context).hasSingleBean(ObjectStorageClient.class);
                var target = context.getBean(ObjectStorageRouter.class).resolve("aliyun");
                assertThat(target.bucket()).isEqualTo("test-bucket");
                assertThat(target.keyPrefix()).isEqualTo("item/ai-item-summary");
            });
    }
    /** OSS 项目目录由服务端配置，去掉首尾斜杠后再叠加公共文件目录。 */
    @Test void aliyunPrefixIsNormalizedWithoutChangingMinioPrefix() {
        runner.withPropertyValues(
            "app.file.storage.aliyun.enabled=true",
            "app.file.storage.aliyun.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
            "app.file.storage.aliyun.bucket=test-bucket",
            "app.file.storage.aliyun.access-key-id=test-access",
            "app.file.storage.aliyun.access-key-secret=test-secret",
            "app.file.storage.aliyun.key-prefix= /item/custom-project/ ",
            "app.file.storage.minio.enabled=true",
            "app.file.storage.minio.endpoint=http://127.0.0.1:19000",
            "app.file.storage.minio.bucket=minio-bucket",
            "app.file.storage.minio.access-key=test-access",
            "app.file.storage.minio.secret-key=test-secret")
            .run(context -> {
                var router = context.getBean(ObjectStorageRouter.class);
                assertThat(router.resolve("aliyun").keyPrefix()).isEqualTo("item/custom-project");
                assertThat(router.resolve("minio").keyPrefix()).isEmpty();
            });
    }

    /** 项目目录不能空白或超过配置字符上限，启用 OSS 时必须在启动阶段拒绝。 */
    @Test void enabledAliyunRejectsInvalidProjectPrefixes() {
        for (String prefix : new String[] {"/", " ", "a".repeat(151)}) {
            runner.withPropertyValues(
                "app.file.storage.aliyun.enabled=true",
                "app.file.storage.aliyun.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
                "app.file.storage.aliyun.bucket=test-bucket",
                "app.file.storage.aliyun.access-key-id=test-access",
                "app.file.storage.aliyun.access-key-secret=test-secret",
                "app.file.storage.aliyun.key-prefix=" + prefix)
                .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test void enabledIncompleteStorageAndUnsupportedTimeoutsFailAtStartup() {
        for (String value : new String[] {"app.file.storage.minio.enabled=true", "app.file.storage.aliyun.enabled=true",
                "app.file.storage.download-url-expiration=0s", "app.file.storage.download-url-expiration=8d",
                "app.file.storage.download-url-expiration=1500ms", "app.file.storage.read-timeout=0s",
                "app.file.upload.max-size=0B"}) {
            runner.withPropertyValues(value).run(context -> assertThat(context).hasFailed());
        }
    }
}
