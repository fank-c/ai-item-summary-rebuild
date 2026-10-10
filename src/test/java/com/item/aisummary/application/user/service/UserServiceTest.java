package com.item.aisummary.application.user.service;

import com.item.aisummary.application.user.command.SaveUserCommand;
import com.item.aisummary.application.user.port.PasswordHasher;
import com.item.aisummary.application.user.port.UserRepository;
import com.item.aisummary.application.user.result.UserProfile;
import com.item.aisummary.domain.user.exception.UserNotFoundException;
import com.item.aisummary.domain.user.model.User;
import com.item.aisummary.infrastructure.security.BCryptPasswordHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 服务规则补充验证：假的仓储不应使已删除用户或失败写入被视为成功。 */
class UserServiceTest {
    private static final Instant TIME = Instant.parse("2026-10-07T01:02:03.123456Z");
    private final UserRepository repository = mock(UserRepository.class);
    private final PasswordHasher hasher = new BCryptPasswordHasher();
    private final UserService service = new UserService(repository, hasher, Clock.fixed(TIME, ZoneOffset.UTC));

    @Test
    void trimsServiceInputsWithoutHttpDependencyAndUsesOneClockValue() {
        when(repository.insert(any())).thenAnswer(call -> {
            User user = call.getArgument(0);
            return new User("id", user.name(), user.email(), user.passwordHash(), user.status(), user.deletedAt(), user.createAt(), user.updateAt());
        });
        UserProfile profile = service.create(new SaveUserCommand(" Alice ", " Alice@Example.test ", " secret "));
        assertThat(profile.name()).isEqualTo("Alice");
        assertThat(profile.email()).isEqualTo("Alice@Example.test");
        assertThat(profile.createAt()).isEqualTo(TIME);
        assertThat(profile.updateAt()).isEqualTo(TIME);
    }

    @Test
    void defensiveQueriesHideDeletedRecordsEvenWhenAdapterReturnsThem() {
        User active = user(null);
        User deleted = user(TIME);
        when(repository.findAll()).thenReturn(List.of(active, deleted));
        when(repository.findById("id")).thenReturn(Optional.of(deleted));
        assertThat(service.findAll()).extracting(UserProfile::id).containsExactly("id");
        assertThatThrownBy(() -> service.findById("id")).isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> service.update("id", new SaveUserCommand("Bob", "bob@example.test", null)))
            .isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> service.delete("id")).isInstanceOf(UserNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void refusesUnexpectedUpdateOrDeleteRowCounts(int rows) {
        when(repository.findById("id")).thenReturn(Optional.of(user(null)));
        when(repository.update(any(), anyBoolean())).thenReturn(rows);
        when(repository.softDelete(eq("id"), any())).thenReturn(rows);
        assertThatIllegalStateException().isThrownBy(() -> service.update("id", new SaveUserCommand("Bob", "bob@example.test", null)));
        assertThatIllegalStateException().isThrownBy(() -> service.delete("id"));
    }

    @Test
    void profileUpdateWithoutPasswordLeavesPasswordUnchangedInRepositoryContract() {
        when(repository.findById("id")).thenReturn(Optional.of(user(null)));
        when(repository.update(any(), anyBoolean())).thenReturn(1);

        UserProfile profile = service.update("id", new SaveUserCommand(" Bob ", " bob@example.test ", null));
        var capture = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(repository).update(capture.capture(), eq(false));
        assertThat(capture.getValue().passwordHash()).isEqualTo("existing-hash");
        assertThat(profile.name()).isEqualTo("Bob");
        assertThat(profile.email()).isEqualTo("bob@example.test");
        assertThat(profile.updateAt()).isEqualTo(TIME);
    }

    @Test
    void explicitPasswordUpdatePassesNewUntrimmedBcryptHash() {
        when(repository.findById("id")).thenReturn(Optional.of(user(null)));
        when(repository.update(any(), anyBoolean())).thenReturn(1);

        service.update("id", new SaveUserCommand("Bob", "bob@example.test", " new secret "));
        var capture = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(repository).update(capture.capture(), eq(true));
        String hash = capture.getValue().passwordHash();
        var bcrypt = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        assertThat(bcrypt.matches(" new secret ", hash)).isTrue();
        assertThat(bcrypt.matches("new secret", hash)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void rejectsBlankIdsInAllSingleUserOperations(String id) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.findById(id));
        assertThatIllegalArgumentException().isThrownBy(() -> service.update(id, new SaveUserCommand("Bob", "bob@example.test", null)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.delete(id));
    }

    @Test
    void rejectsNullIdAndInvalidServiceText() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.findById(null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.create(new SaveUserCommand(" ", "alice@example.test", "secret")));
        assertThatIllegalArgumentException().isThrownBy(() -> service.create(new SaveUserCommand("Alice", null, "secret")));
    }

    private static User user(Instant deletedAt) {
        return new User("id", "Alice", "alice@example.test", "existing-hash", 7, deletedAt, TIME, TIME);
    }
}
