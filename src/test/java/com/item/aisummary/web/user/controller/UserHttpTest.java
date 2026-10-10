package com.item.aisummary.web.user.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP 合同和数据库副作用：抓住密码泄露、错误更新和失效的逻辑删除过滤。 */
@SpringBootTest
@AutoConfigureMockMvc
class UserHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    @BeforeEach
    void resetUsers() {
        jdbc.update("DELETE FROM `user`");
    }

    @Test
    void createsUserWithOnlyPublicFieldsAndSaltedUntrimmedPassword() throws Exception {
        MvcResult result = mvc.perform(post("/api/users").contentType(APPLICATION_JSON)
                .content(body(" Alice ", "Alice@Example.test", " secret ")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Alice"))
            .andExpect(jsonPath("$.email").value("Alice@Example.test"))
            .andExpect(jsonPath("$.status").value(1))
            .andExpect(jsonPath("$.createAt").isString())
            .andExpect(jsonPath("$.updateAt").isString())
            .andReturn();
        Map<?, ?> response = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(response.keySet().stream().map(Object::toString).toList())
            .containsExactlyInAnyOrder("id", "name", "email", "status", "createAt", "updateAt");
        String id = id(result);
        assertThat(id).matches("[0-9a-fA-F]{32}");
        String hash = hash(id);
        assertThat(hash).matches("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}");
        assertThat(bcrypt.matches(" secret ", hash)).isTrue();
        assertThat(bcrypt.matches("secret", hash)).isFalse();
        assertThat(response.get("createAt")).isEqualTo(response.get("updateAt"));
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM `user` WHERE id=?", Object.class, id)).isNull();
    }

    @Test
    void duplicateNamesAndEmailsAreAllowedAndEachHashHasItsOwnSalt() throws Exception {
        String first = create("Alice", "alice@example.test", "secret");
        String second = create("Alice", "alice@example.test", "secret");
        assertThat(second).isNotEqualTo(first);
        assertThat(hash(second)).isNotEqualTo(hash(first));
        mvc.perform(get("/api/users")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void returnsCreatedUserAndTrimsPathId() throws Exception {
        String id = create("Alice", "alice@example.test", "secret");
        mvc.perform(get("/api/users/{id}", " " + id + " "))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.deletedAt").doesNotExist());
    }

    @Test
    void omittedOrNullPasswordPreservesHashWhileNewPasswordReplacesIt() throws Exception {
        String id = create("Alice", "alice@example.test", "secret");
        jdbc.update("UPDATE `user` SET status=0 WHERE id=?", id);
        String originalHash = hash(id);
        String originalCreated = JsonPath.read(mvc.perform(get("/api/users/{id}", id))
            .andReturn().getResponse().getContentAsString(), "$.createAt");
        mvc.perform(put("/api/users/{id}", id).contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", " Bob ", "email", "Bob@example.test"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Bob"))
            .andExpect(jsonPath("$.status").value(0)).andExpect(jsonPath("$.createAt").value(originalCreated));
        assertThat(hash(id)).isEqualTo(originalHash);
        mvc.perform(put("/api/users/{id}", id).contentType(APPLICATION_JSON)
                .content(body("Bob", "bob@example.test", null)))
            .andExpect(status().isOk());
        assertThat(hash(id)).isEqualTo(originalHash);
        mvc.perform(put("/api/users/{id}", id).contentType(APPLICATION_JSON)
                .content(body("Bob", "bob@example.test", " new secret ")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertThat(bcrypt.matches(" new secret ", hash(id))).isTrue();
        assertThat(bcrypt.matches("secret", hash(id))).isFalse();
    }

    @Test
    void deleteKeepsPhysicalRecordAndStatusAndHidesUserEverywhere() throws Exception {
        String id = create("Alice", "alice@example.test", "secret");
        jdbc.update("UPDATE `user` SET status=7 WHERE id=?", id);
        String password = hash(id);
        mvc.perform(delete("/api/users/{id}", id)).andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM `user` WHERE id=?", Integer.class, id)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM `user` WHERE id=?", Object.class, id)).isNotNull();
        assertThat(hash(id)).isEqualTo(password);
        mvc.perform(get("/api/users")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/users/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(put("/api/users/{id}", id).contentType(APPLICATION_JSON).content(body("Bob", "bob@example.test", null)))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/users/{id}", id)).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void blankUpdatePasswordIsRejectedWithoutChangingHash(String password) throws Exception {
        String id = create("Alice", "alice@example.test", "secret");
        String previous = hash(id);
        mvc.perform(put("/api/users/{id}", id).contentType(APPLICATION_JSON)
                .content(body("Bob", "bob@example.test", password)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_ERROR"));
        assertThat(hash(id)).isEqualTo(previous);
    }

    @Test
    void putRequiresBothNameAndEmail() throws Exception {
        String id = create("Alice", "alice@example.test", "secret");
        mvc.perform(put("/api/users/{id}", id).contentType(APPLICATION_JSON).content("{\"name\":\"Bob\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("email"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "invalid", " alice@example.test "})
    void httpEmailValidationRunsBeforeTrimming(String email) throws Exception {
        mvc.perform(post("/api/users").contentType(APPLICATION_JSON).content(body("Alice", email, "secret")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user`", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void blankCreateFieldsAreRejectedWithoutEchoingInputs(String value) throws Exception {
        mvc.perform(post("/api/users").contentType(APPLICATION_JSON).content(body(value, "alice@example.test", value)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray())
            .andExpect(jsonPath("$.errors[*].rejectedValue").doesNotExist())
            .andExpect(jsonPath("$.errors[*].password").doesNotExist());
    }

    @Test
    void characterLengthLimitsAreEnforcedAndValidationErrorsAreSafe() throws Exception {
        MvcResult result = mvc.perform(post("/api/users").contentType(APPLICATION_JSON)
                .content(body("a".repeat(101), "a".repeat(245) + "@example.test", "SECRET_MARKER".repeat(26))))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").exists()).andExpect(jsonPath("$.title").isString())
            .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.detail").isString())
            .andExpect(jsonPath("$.instance").value("/api/users"))
            .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_ERROR"))
            .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("SECRET_MARKER");
        java.util.List<Map<String, Object>> errors = JsonPath.read(result.getResponse().getContentAsString(), "$.errors");
        assertThat(errors).allSatisfy(error -> assertThat(error.keySet()).containsExactlyInAnyOrder("field", "message"));
    }

    @Test
    void bcryptBoundaryIsMeasuredInUtf8BytesWithoutTruncation() throws Exception {
        String ascii = create("ASCII", "ascii@example.test", "a".repeat(72));
        assertThat(bcrypt.matches("a".repeat(72), hash(ascii))).isTrue();
        String chinese = create("Unicode", "unicode@example.test", "密".repeat(24));
        assertThat(bcrypt.matches("密".repeat(24), hash(chinese))).isTrue();
        for (String tooLong : new String[] {"a".repeat(73), "密".repeat(25)}) {
            mvc.perform(post("/api/users").contentType(APPLICATION_JSON).content(body("Alice", "alice@example.test", tooLong)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_ERROR"));
        }
    }

    @Test
    void malformedJsonAndMissingBodyHaveTheirOwnErrorCode() throws Exception {
        mvc.perform(post("/api/users").contentType(APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));
        mvc.perform(post("/api/users").contentType(APPLICATION_JSON))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));
    }

    @Test
    void missingAndBlankIdsReturnProblemDetailsWithRequestId() throws Exception {
        mvc.perform(get("/api/users/missing").header("X-Request-Id", "test-request-1"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.requestId").value("test-request-1"))
            .andExpect(header().string("X-Request-Id", "test-request-1"));
        mvc.perform(get("/api/users/{id}", " ")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_ERROR"));
        mvc.perform(put("/api/users/missing").contentType(APPLICATION_JSON).content(body("Bob", "bob@example.test", null)))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/users/missing")).andExpect(status().isNotFound());
    }

    String create(String name, String email, String password) throws Exception {
        return id(mvc.perform(post("/api/users").contentType(APPLICATION_JSON).content(body(name, email, password)))
            .andExpect(status().isCreated()).andReturn());
    }

    String body(String name, String email, String password) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", name); fields.put("email", email); fields.put("password", password);
        return json.writeValueAsString(fields);
    }

    private String id(MvcResult result) throws java.io.UnsupportedEncodingException {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private String hash(String id) {
        return jdbc.queryForObject("SELECT password FROM `user` WHERE id=?", String.class, id);
    }
}
