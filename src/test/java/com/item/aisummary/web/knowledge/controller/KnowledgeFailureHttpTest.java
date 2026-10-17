package com.item.aisummary.web.knowledge.controller;

import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildException;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildStage;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeIndexBuildService;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingMode;
import com.item.aisummary.application.knowledge.service.KnowledgeDocumentService;
import com.item.aisummary.domain.knowledge.enums.DocumentIndexStatus;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocument;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 隔离构建失败阶段，验证 HTTP 状态区分、固定脱敏摘要及发布结果不明的保守表达。 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeFailureHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean KnowledgeIndexBuildService builds;
    @MockitoBean KnowledgeDocumentService documents;

    @ParameterizedTest
    @EnumSource(KnowledgeBuildStage.class)
    void buildFailuresUseSafeStageMessagesAndNeverExposeCause(KnowledgeBuildStage stage) throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        when(documents.findById("document")).thenReturn(new KnowledgeDocument("document", "base", "file", 1,
                DocumentIndexStatus.PENDING, null, null, null, now, now));
        when(builds.build("mock-user", "document", ChunkingMode.FLAT)).thenThrow(
                new KnowledgeBuildException(stage, stage.name() + "_FAILED", "PRIVATE_TOP_LEVEL_MESSAGE", false,
                        new IllegalStateException("PRIVATE_PROVIDER_BODY")));
        int expected = switch (stage) {
            case SOURCE_READ, VECTOR_WRITE -> 502;
            case PARSE, CHUNK -> 422;
            case CHUNK_SAVE, PUBLISH -> 500;
        };
        var result = mvc.perform(post("/api/v1/knowledge-documents/document/builds")
                        .header("X-Request-Id", "knowledge-failure"))
                .andExpect(status().is(expected))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value(stage.name() + "_FAILED"))
                .andExpect(jsonPath("$.detail").value(stage.safeMessage()))
                .andExpect(jsonPath("$.stage").value(stage.name()))
                .andExpect(jsonPath("$.cleanupCompleted").value(false))
                .andExpect(jsonPath("$.requestId").value("knowledge-failure"))
                .andExpect(jsonPath("$.instance").value("/api/v1/knowledge-documents/document/builds"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("PRIVATE_TOP_LEVEL_MESSAGE", "PRIVATE_PROVIDER_BODY", "cause", "stackTrace");
    }

    /** 不接受未知枚举名或数字序号，避免客户端误用内部枚举顺序触发真实构建。 */
    @ParameterizedTest
    @ValueSource(strings = {"{\"mode\":\"unknown\"}", "{\"mode\":0}", "{\"mode\":\"0\"}", "{\"mode\":{}}"})
    void invalidModesAreRejectedBeforeBuilding(String body) throws Exception {
        mvc.perform(post("/api/v1/knowledge-documents/document/builds")
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));
    }

    @Test
    void unknownDatabaseFailureIsStillGenericInternalError() throws Exception {
        when(documents.findById("document")).thenThrow(new IllegalStateException("PRIVATE_SQL"));
        var response = mvc.perform(get("/api/v1/knowledge-documents/document"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("服务器内部错误")).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("PRIVATE_SQL");
    }
}
