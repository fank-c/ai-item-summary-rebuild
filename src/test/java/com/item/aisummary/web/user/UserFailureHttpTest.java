package com.item.aisummary.web.user;

import com.item.aisummary.application.user.port.UserRepository;
import com.item.aisummary.domain.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 隔离外部写入失败，验证真实服务和 HTTP 错误映射不会误报成功或回显内部异常。 */
@SpringBootTest
@AutoConfigureMockMvc
class UserFailureHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserRepository repository;

    @Test
    void zeroRowUpdatesAndDeletesReturnInternalError() throws Exception {
        Instant time = Instant.parse("2026-10-07T00:00:00Z");
        when(repository.findById("id")).thenReturn(Optional.of(new User("id", "Alice", "alice@example.test", "hash", 1, null, time, time)));
        when(repository.update(any(), anyBoolean())).thenReturn(0);
        when(repository.softDelete(eq("id"), any())).thenReturn(0);
        mvc.perform(put("/api/users/id").contentType("application/json")
                .content("{\"name\":\"Bob\",\"email\":\"bob@example.test\"}"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        mvc.perform(delete("/api/users/id")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("服务器内部错误"));
    }

    @Test
    void unexpectedExceptionDoesNotExposeItsMessageOrRequestPassword() throws Exception {
        when(repository.insert(any())).thenThrow(new IllegalStateException("DATABASE_PRIVATE_MARKER"));
        var result = mvc.perform(post("/api/users").contentType("application/json")
                .content("{\"name\":\"Alice\",\"email\":\"alice@example.test\",\"password\":\"PASSWORD_PRIVATE_MARKER\"}"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.detail").value("服务器内部错误")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("DATABASE_PRIVATE_MARKER", "PASSWORD_PRIVATE_MARKER");
    }
}
