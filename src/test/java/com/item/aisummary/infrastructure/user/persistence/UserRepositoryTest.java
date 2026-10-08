package com.item.aisummary.infrastructure.user.persistence;

import com.item.aisummary.application.user.port.UserRepository;
import com.item.aisummary.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 Mapper 的 SQL 行为和 UTC 微秒映射；H2 结果不代替 MySQL 验收。 */
@SpringBootTest
class UserRepositoryTest {
    @Autowired UserRepository repository;
    @Autowired JdbcTemplate jdbc;
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05.123456Z");
    private static final Instant UPDATED = Instant.parse("2026-01-03T04:05:06.654321Z");

    @BeforeEach
    void resetUsers() {
        jdbc.update("DELETE FROM `user`");
    }

    @Test
    void persistsUtcMicrosAndChangesOnlyDeletionAndUpdateTimes() {
        User inserted = repository.insert(new User(null, "Alice", "Alice@example.test", "existing-hash", 7, null, CREATED, CREATED));
        User fetched = repository.findById(inserted.id()).orElseThrow();
        assertThat(fetched.createAt()).isEqualTo(CREATED);
        assertThat(fetched.updateAt()).isEqualTo(CREATED);
        assertThat(jdbc.queryForObject("SELECT create_at FROM `user` WHERE id=?", LocalDateTime.class, inserted.id()))
            .isEqualTo(LocalDateTime.parse("2026-01-02T03:04:05.123456"));
        assertThat(repository.softDelete(inserted.id(), UPDATED)).isEqualTo(1);
        assertThat(repository.findById(inserted.id())).isEmpty();
        assertThat(repository.findAll()).isEmpty();
        var stored = jdbc.queryForMap("SELECT * FROM `user` WHERE id=?", inserted.id());
        assertThat(stored.get("name")).isEqualTo("Alice");
        assertThat(stored.get("email")).isEqualTo("Alice@example.test");
        assertThat(stored.get("password")).isEqualTo("existing-hash");
        assertThat(stored.get("status")).isEqualTo(7);
        assertThat(stored.get("deleted_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT update_at FROM `user` WHERE id=?", LocalDateTime.class, inserted.id()))
            .isEqualTo(LocalDateTime.parse("2026-01-03T04:05:06.654321"));
        assertThat(repository.softDelete(inserted.id(), UPDATED)).isZero();
        assertThat(repository.update(inserted)).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void rejectsUnexpectedInsertRowCounts(int rows) {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.insert(any(UserEntity.class))).thenReturn(rows);
        MyBatisUserRepository adapter = new MyBatisUserRepository(mapper);
        assertThatIllegalStateException().isThrownBy(() -> adapter.insert(
            new User(null, "Alice", "alice@example.test", "hash", 1, null, CREATED, CREATED)));
    }
}
