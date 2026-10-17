package com.item.aisummary.web.knowledge.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 关闭模拟身份后验证所有知识库入口均拒绝匿名调用，客户端声明身份不能替代请求上下文。 */
@SpringBootTest(properties = "app.auth.mock.enabled=false")
@AutoConfigureMockMvc
class KnowledgeAuthenticationHttpTest {
    @Autowired MockMvc mvc;

    @Test
    void everyKnowledgeEndpointRequiresServerIdentity() throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(
                get("/api/v1/knowledge-bases"), get("/api/v1/knowledge-bases/id"),
                post("/api/v1/knowledge-bases").contentType("application/json").content("{\"name\":\"资料\"}"),
                put("/api/v1/knowledge-bases/id").contentType("application/json").content("{\"name\":\"资料\"}"),
                get("/api/v1/knowledge-bases/id/documents"),
                post("/api/v1/knowledge-bases/id/documents").contentType("application/json").content("{\"fileId\":\"file\"}"),
                get("/api/v1/knowledge-documents/id"), post("/api/v1/knowledge-documents/id/builds"),
                get("/api/v1/knowledge-documents/id/revisions"), get("/api/v1/knowledge-revisions/id"),
                get("/api/v1/knowledge-revisions/id/chunks"), get("/api/v1/knowledge-chunks/id"));
        for (var request : requests) {
            mvc.perform(request.header("Authorization", "Bearer fake").param("actorUserId", "mock-user"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
    }
}
