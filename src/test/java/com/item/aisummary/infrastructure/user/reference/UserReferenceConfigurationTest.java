package com.item.aisummary.infrastructure.user.reference;

import com.item.aisummary.application.user.reference.UserReference;
import com.item.aisummary.application.user.reference.UserReferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;

import static org.assertj.core.api.Assertions.assertThat;

/** 配置边界：没有数据库时仍能获得固定引用，且任何模式只能装配一个实现。 */
class UserReferenceConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Scan.class);

    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.item.aisummary.infrastructure.user.reference")
    static class Scan { }

    @Test
    void defaultMockModeNeedsNoRepositoryAndUsesConfiguredUser() {
        runner.withPropertyValues("app.auth.mock.user-id= fixed-user ", "app.auth.mock.username= Fixed User ")
            .run(context -> {
                assertThat(context).hasSingleBean(UserReferenceService.class);
                UserReferenceService service = context.getBean(UserReferenceService.class);
                assertThat(service.requireExisting(" fixed-user ")).isEqualTo(new UserReference("fixed-user", "Fixed User"));
                org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.requireExisting("other"));
                org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.requireExisting(null));
            });
    }

    @Test
    void enabledMockModeRejectsBlankConfiguredIdentity() {
        runner.withPropertyValues("app.auth.mock.user-id= ").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.auth.mock.username= ").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void disabledMockModeUsesDatabaseUserQueryWithoutFilteringStatus() {
        com.item.aisummary.application.user.port.UserRepository repository = org.mockito.Mockito.mock(
            com.item.aisummary.application.user.port.UserRepository.class);
        java.time.Instant time = java.time.Instant.parse("2026-10-07T00:00:00Z");
        org.mockito.Mockito.when(repository.findById("existing")).thenReturn(java.util.Optional.of(
            new com.item.aisummary.domain.user.User("existing", "Real User", "real@example.test", "hash", 0, null, time, time)));
        org.mockito.Mockito.when(repository.findById("deleted")).thenReturn(java.util.Optional.of(
            new com.item.aisummary.domain.user.User("deleted", "Deleted User", "deleted@example.test", "hash", 1, time, time, time)));
        runner.withPropertyValues("app.auth.mock.enabled=false", "app.auth.mock.user-id=", "app.auth.mock.username=")
            .withBean(com.item.aisummary.application.user.UserService.class, () -> new com.item.aisummary.application.user.UserService(
                repository, org.mockito.Mockito.mock(com.item.aisummary.application.user.port.PasswordHasher.class), java.time.Clock.systemUTC()))
            .run(context -> {
                assertThat(context).hasSingleBean(UserReferenceService.class);
                UserReferenceService service = context.getBean(UserReferenceService.class);
                assertThat(service.requireExisting(" existing ")).isEqualTo(new UserReference("existing", "Real User"));
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requireExisting("missing"))
                    .isInstanceOf(com.item.aisummary.domain.user.UserNotFoundException.class);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requireExisting("deleted"))
                    .isInstanceOf(com.item.aisummary.domain.user.UserNotFoundException.class);
                org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.requireExisting(" "));
            });
    }
}
