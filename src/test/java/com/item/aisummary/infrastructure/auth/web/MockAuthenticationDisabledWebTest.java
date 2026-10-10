package com.item.aisummary.infrastructure.auth.web;

import com.item.aisummary.application.user.reference.UserReferenceService;
import com.item.aisummary.domain.user.exception.UserNotFoundException;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 关闭模拟身份后需要身份的能力返回 401，用户 CRUD 仍开放。 */
@SpringBootTest(properties = "app.auth.mock.enabled=false")
@AutoConfigureMockMvc
@Import(MockAuthenticationWebTest.ProbeController.class)
class MockAuthenticationDisabledWebTest {
    @Autowired MockMvc mvc;
    @Autowired UserReferenceService references;

    @Test
    void missingIdentityReturnsAuthenticationProblem() throws Exception {
        mvc.perform(get("/api/test/current-user").header("Authorization", "Bearer ignored"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
            .andExpect(jsonPath("$.type").exists()).andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void crudRemainsOpenAndReferencesUseRealUserRows() throws Exception {
        String body = mvc.perform(post("/api/users").contentType("application/json")
                .content("{\"name\":\"Real User\",\"email\":\"real@example.test\",\"password\":\"secret\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        assertThat(references.requireExisting(id).name()).isEqualTo("Real User");
        mvc.perform(get("/api/users/{id}", id)).andExpect(status().isOk());
        mvc.perform(delete("/api/users/{id}", id)).andExpect(status().isNoContent());
        assertThatThrownBy(() -> references.requireExisting(id)).isInstanceOf(UserNotFoundException.class);
    }
}
