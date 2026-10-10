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
    void enabledMockModeAccepts64CodePointsAfterTrimmingAndRejects65() {
        String supplementary = new String(Character.toChars(0x1F600));
        for (String character : new String[] {"a", supplementary}) {
            String accepted = character.repeat(64);
            runner.withPropertyValues("app.auth.mock.user-id= " + accepted + " ").run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(UserReferenceService.class);
                assertThat(context.getBean(UserReferenceService.class).requireExisting(accepted).id()).isEqualTo(accepted);
            });
            runner.withPropertyValues("app.auth.mock.user-id=" + character.repeat(65))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .hasRootCauseMessage("用户 ID 长度不能超过 64 个字符");
                });
        }
    }

    @Test
    void disabledMockModeAllowsUnusedOverlongIdentity() {
        runner.withPropertyValues("app.auth.mock.enabled=false", "app.auth.mock.user-id=" + "a".repeat(65),
                "app.auth.mock.username=")
            .withBean(com.item.aisummary.application.user.service.UserService.class, () -> new com.item.aisummary.application.user.service.UserService(
                org.mockito.Mockito.mock(com.item.aisummary.application.user.port.UserRepository.class),
                org.mockito.Mockito.mock(com.item.aisummary.application.user.port.PasswordHasher.class), java.time.Clock.systemUTC()))
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(UserReferenceService.class));
    }

    @Test
    void disabledMockModeUsesDatabaseUserQueryWithoutFilteringStatus() {
        com.item.aisummary.application.user.port.UserRepository repository = org.mockito.Mockito.mock(
            com.item.aisummary.application.user.port.UserRepository.class);
        java.time.Instant time = java.time.Instant.parse("2026-10-07T00:00:00Z");
        org.mockito.Mockito.when(repository.findById("existing")).thenReturn(java.util.Optional.of(
            new com.item.aisummary.domain.user.model.User("existing", "Real User", "real@example.test", "hash", 0, null, time, time)));
        org.mockito.Mockito.when(repository.findById("deleted")).thenReturn(java.util.Optional.of(
            new com.item.aisummary.domain.user.model.User("deleted", "Deleted User", "deleted@example.test", "hash", 1, time, time, time)));
        runner.withPropertyValues("app.auth.mock.enabled=false", "app.auth.mock.user-id=", "app.auth.mock.username=")
            .withBean(com.item.aisummary.application.user.service.UserService.class, () -> new com.item.aisummary.application.user.service.UserService(
                repository, org.mockito.Mockito.mock(com.item.aisummary.application.user.port.PasswordHasher.class), java.time.Clock.systemUTC()))
            .run(context -> {
                assertThat(context).hasSingleBean(UserReferenceService.class);
                UserReferenceService service = context.getBean(UserReferenceService.class);
                assertThat(service.requireExisting(" existing ")).isEqualTo(new UserReference("existing", "Real User"));
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requireExisting("missing"))
                    .isInstanceOf(com.item.aisummary.domain.user.exception.UserNotFoundException.class);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requireExisting("deleted"))
                    .isInstanceOf(com.item.aisummary.domain.user.exception.UserNotFoundException.class);
                org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.requireExisting(" "));
            });
    }
}
