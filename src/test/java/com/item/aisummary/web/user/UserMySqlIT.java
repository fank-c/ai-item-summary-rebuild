package com.item.aisummary.web.user;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 显式 mysql-it profile 才执行，只接受以 _test 结尾的专用 MySQL 数据库。 */
@SpringBootTest(properties = {"spring.sql.init.mode=never", "app.auth.mock.enabled=false"})
@AutoConfigureMockMvc
class UserMySqlIT {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        String url = System.getenv("USER_TEST_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/[A-Za-z0-9_]+_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("USER_TEST_DB_URL 必须指定名称以 _test 结尾的独立 MySQL 测试库");
        }
        String username = System.getenv("USER_TEST_DB_USERNAME");
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("必须设置 USER_TEST_DB_USERNAME");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("USER_TEST_DB_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void mysqlSchemaAndHttpLifecyclePreserveHashStatusAndUtcMicros() throws Exception {
        new ResourceDatabasePopulator(new FileSystemResource("deploy/mysql/schema.sql")).execute(datasource);
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isIn("+00:00", "UTC");
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
        List<String> createdIds = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                String body = mvc.perform(post("/api/users").contentType("application/json")
                        .content("{\"name\":\" Alice \",\"email\":\"Alice@Example.test\",\"password\":\" secret \"}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Alice"))
                    .andExpect(jsonPath("$.password").doesNotExist()).andReturn().getResponse().getContentAsString();
                String id = JsonPath.read(body, "$.id");
                createdIds.add(id);
                String created = JsonPath.read(body, "$.createAt");
                assertThat(jdbc.queryForObject("SELECT create_at FROM `user` WHERE id=?", LocalDateTime.class, id))
                    .isEqualTo(LocalDateTime.ofInstant(Instant.parse(created), ZoneOffset.UTC));
                mvc.perform(get("/api/users/{id}", id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.createAt").value(created));
            }
            String id = createdIds.getFirst();
            String original = hash(id);
            assertThat(bcrypt.matches(" secret ", original)).isTrue();
            assertThat(hash(createdIds.getLast())).isNotEqualTo(original);
            jdbc.update("UPDATE `user` SET status=7 WHERE id=?", id);
            mvc.perform(put("/api/users/{id}", id).contentType("application/json")
                    .content("{\"name\":\"Bob\",\"email\":\"bob@example.test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(7));
            assertThat(hash(id)).isEqualTo(original);
            mvc.perform(put("/api/users/{id}", id).contentType("application/json")
                    .content("{\"name\":\"Bob\",\"email\":\"bob@example.test\",\"password\":\" new secret \"}"))
                .andExpect(status().isOk());
            String changed = hash(id);
            assertThat(bcrypt.matches(" new secret ", changed)).isTrue();
            assertThat(bcrypt.matches(" secret ", changed)).isFalse();
            mvc.perform(delete("/api/users/{id}", id)).andExpect(status().isNoContent());
            assertThat(jdbc.queryForObject("SELECT status FROM `user` WHERE id=?", Integer.class, id)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT deleted_at FROM `user` WHERE id=?", LocalDateTime.class, id)).isNotNull();
            assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, deleted_at, CURRENT_TIMESTAMP(6)) FROM `user` WHERE id=?", Long.class, id))
                .isBetween(0L, 5L);
            assertThat(hash(id)).isEqualTo(changed);
            mvc.perform(get("/api/users/{id}", id)).andExpect(status().isNotFound());
            mvc.perform(delete("/api/users/{id}", id)).andExpect(status().isNotFound());
            List<String> visible = JsonPath.read(mvc.perform(get("/api/users")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$[*].id");
            assertThat(visible).contains(createdIds.getLast()).doesNotContain(id);
        } finally {
            // 只清理本次创建的记录，测试库中的其他数据不受影响。
            for (String id : createdIds) {
                jdbc.update("DELETE FROM `user` WHERE id=?", id);
            }
        }
    }

    private String hash(String id) {
        return jdbc.queryForObject("SELECT password FROM `user` WHERE id=?", String.class, id);
    }
}
