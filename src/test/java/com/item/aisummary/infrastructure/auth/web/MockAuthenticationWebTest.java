package com.item.aisummary.infrastructure.auth.web;

import com.item.aisummary.application.auth.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 请求身份行为：认证头不能改变固定身份；身份不能泄漏到下一请求或 Session。 */
@SpringBootTest(properties = {"app.auth.mock.user-id=fixed-user", "app.auth.mock.username=Fixed User"})
@AutoConfigureMockMvc
@Import(MockAuthenticationWebTest.ProbeController.class)
class MockAuthenticationWebTest {
    @Autowired MockMvc mvc;

    @Test
    void ignoresAuthorizationAndUsesConfiguredIdentity() throws Exception {
        for (String header : new String[] {"", "Bearer invalid", "Bearer another-user"}) {
            mvc.perform(get("/api/test/current-user").header("Authorization", header))
                .andExpect(status().isOk()).andExpect(content().string("fixed-user:Fixed User"));
        }
        mvc.perform(get("/api/test/current-user")).andExpect(content().string("fixed-user:Fixed User"));
    }

    @Test
    void removesIdentityAfterSuccessAndExceptionAndNeverCreatesSession() throws Exception {
        var success = mvc.perform(get("/api/test/current-user")).andExpect(status().isOk()).andReturn();
        assertThat(success.getRequest().getAttribute(CurrentUser.class.getName())).isNull();
        assertThat(success.getRequest().getSession(false)).isNull();
        var failure = mvc.perform(get("/api/test/failure")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.detail").value("服务器内部错误")).andReturn();
        assertThat(failure.getResponse().getContentAsString()).doesNotContain("PRIVATE_MARKER");
        assertThat(failure.getRequest().getAttribute(CurrentUser.class.getName())).isNull();
    }

    @Test
    void skipsOptionsAndNonApiRequests() throws Exception {
        mvc.perform(options("/api/test/options")).andExpect(status().isOk()).andExpect(content().string("anonymous"));
        mvc.perform(get("/outside/test/current-user")).andExpect(status().isOk()).andExpect(content().string("anonymous"));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/test/current-user")
        String current(HttpServletRequest request) {
            CurrentUser user = LoginUserContext.require(request);
            return user.id() + ":" + user.username();
        }

        @RequestMapping(path = "/api/test/options", method = RequestMethod.OPTIONS)
        String optionsProbe(HttpServletRequest request) {
            return request.getAttribute(CurrentUser.class.getName()) == null ? "anonymous" : "unexpected-user";
        }

        @GetMapping("/outside/test/current-user")
        String outside(HttpServletRequest request) {
            return request.getAttribute(CurrentUser.class.getName()) == null ? "anonymous" : "unexpected-user";
        }

        @GetMapping("/api/test/failure")
        String fail(HttpServletRequest request) {
            current(request);
            throw new IllegalStateException("PRIVATE_MARKER");
        }
    }
}
