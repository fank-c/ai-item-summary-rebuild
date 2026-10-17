package com.item.aisummary.web.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.item.aisummary.application.file.content.FileContentService;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildLifecycleService;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeIndexBuildService;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeProcessingRouter;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingMode;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingOptions;
import com.item.aisummary.application.knowledge.indexing.chunk.KnowledgeChunkGroupWriter;
import com.item.aisummary.application.knowledge.indexing.chunk.KnowledgeChunker;
import com.item.aisummary.application.knowledge.indexing.parse.KnowledgeDocumentParser;
import com.item.aisummary.application.knowledge.indexing.source.KnowledgeSourceReader;
import com.item.aisummary.application.knowledge.indexing.vector.KnowledgeVectorEntry;
import com.item.aisummary.application.knowledge.indexing.vector.KnowledgeVectorIndex;
import com.item.aisummary.application.knowledge.port.KnowledgeChunkRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.application.knowledge.service.KnowledgeChunkService;
import com.item.aisummary.application.knowledge.service.KnowledgeRevisionService;
import com.item.aisummary.domain.knowledge.enums.ChunkRole;
import com.item.aisummary.infrastructure.knowledge.indexing.chunk.TokenBudgetKnowledgeChunker;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.txt.TextKnowledgeDocumentParser;
import com.item.aisummary.web.file.controller.FileStorageTestConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 知识库构建与版本/切片查询的 HTTP 控制层集成测试。
 *
 * <p>基于 MockMvc 与 H2 四表，装配真实文件上传、真实纯文本解析策略、Token 预算切分策略及短事务代理，
 * 配合向量存储替身验证全链路构建发布、父子关系、重建旧版保护、异常脱敏、参数校验与路由边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({FileStorageTestConfiguration.class, KnowledgeBuildHttpTest.KnowledgeBuildTestConfiguration.class})
class KnowledgeBuildHttpTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FileStorageTestConfiguration.MemoryStorage storage;

    @Autowired
    private VectorIndexFixture fixture;

    @Autowired
    private KnowledgeRevisionService revisionService;

    @BeforeEach
    void resetEnvironment() {
        jdbc.update("DELETE FROM knowledge_chunk");
        jdbc.update("DELETE FROM knowledge_document_revision");
        jdbc.update("DELETE FROM knowledge_document");
        jdbc.update("DELETE FROM knowledge_base");
        jdbc.update("DELETE FROM file_asset");
        storage.reset();
        fixture.reset();
    }

    /** 验证默认无 body 时采用 FLAT 模式构建全链路，版本就绪并可查询文档、版本和切片。 */
    @Test
    void defaultFlatModeBuildFullChainReturns200AndPublishesReadyRevision() throws Exception {
        String fileContent = "第一段：这是纯文本测试文档的第一段自然段落内容。\n\n"
                + "第二段：这是纯文本测试文档的第二段自然段落内容。\n\n"
                + "第三段：这是纯文本测试文档的第三段自然段落内容。";
        String fileId = uploadFile("flat-sample.txt", "text/plain", fileContent.getBytes(StandardCharsets.UTF_8));
        String baseId = createKnowledgeBase("平铺模式测试库");
        String documentId = registerDocument(baseId, fileId);

        mvc.perform(get("/api/v1/knowledge-documents/{documentId}", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(documentId))
                .andExpect(jsonPath("$.knowledgeBaseId").value(baseId))
                .andExpect(jsonPath("$.fileId").value(fileId))
                .andExpect(jsonPath("$.indexStatus").value("pending"))
                .andExpect(jsonPath("$.activeRevisionId").doesNotExist());

        var buildResult = mvc.perform(post("/api/v1/knowledge-documents/{documentId}/builds", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(documentId))
                .andExpect(jsonPath("$.revisionId").isNotEmpty())
                .andExpect(jsonPath("$.chunkCount").isNumber())
                .andExpect(jsonPath("$.retrievalChunkCount").isNumber())
                .andExpect(jsonPath("$.oldRevisionCleanupCompleted").value(true))
                .andReturn();

        String responseBody = buildResult.getResponse().getContentAsString();
        String revisionId = JsonPath.read(responseBody, "$.revisionId");
        int chunkCount = JsonPath.read(responseBody, "$.chunkCount");
        int retrievalChunkCount = JsonPath.read(responseBody, "$.retrievalChunkCount");

        assertThat(chunkCount).isGreaterThan(0);
        assertThat(retrievalChunkCount).isEqualTo(chunkCount);

        mvc.perform(get("/api/v1/knowledge-documents/{documentId}", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(documentId))
                .andExpect(jsonPath("$.indexStatus").value("indexed"))
                .andExpect(jsonPath("$.activeRevisionId").value(revisionId))
                .andExpect(jsonPath("$.lastFailureCode").doesNotExist())
                .andExpect(jsonPath("$.lastFailureMessage").doesNotExist());

        mvc.perform(get("/api/v1/knowledge-documents/{documentId}/revisions", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(revisionId))
                .andExpect(jsonPath("$[0].knowledgeDocumentId").value(documentId))
                .andExpect(jsonPath("$[0].revisionNumber").value(1))
                .andExpect(jsonPath("$[0].revisionStatus").value("ready"))
                .andExpect(jsonPath("$[0].chunkCount").value(chunkCount))
                .andExpect(jsonPath("$[0].sourceSha256").isNotEmpty());

        mvc.perform(get("/api/v1/knowledge-revisions/{revisionId}", revisionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(revisionId))
                .andExpect(jsonPath("$.revisionStatus").value("ready"))
                .andExpect(jsonPath("$.chunkCount").value(chunkCount));

        var chunkResult = mvc.perform(get("/api/v1/knowledge-revisions/{revisionId}/chunks", revisionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(chunkCount))
                .andExpect(jsonPath("$[0].chunkRole").value("RETRIEVAL"))
                .andExpect(jsonPath("$[0].parentChunkId").doesNotExist())
                .andExpect(jsonPath("$[0].sequenceNo").value(0))
                .andReturn();

        String firstChunkId = JsonPath.read(chunkResult.getResponse().getContentAsString(), "$[0].id");

        mvc.perform(get("/api/v1/knowledge-chunks/{chunkId}", firstChunkId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstChunkId))
                .andExpect(jsonPath("$.revisionId").value(revisionId))
                .andExpect(jsonPath("$.chunkRole").value("RETRIEVAL"))
                .andExpect(jsonPath("$.parentChunkId").doesNotExist())
                .andExpect(jsonPath("$.content").isNotEmpty());

        assertThat(fixture.entries).hasSize(retrievalChunkCount);
        assertThat(fixture.entries).allSatisfy(entry -> {
            assertThat(entry.chunk().chunkRole()).isEqualTo(ChunkRole.RETRIEVAL);
            assertThat(entry.chunk().revisionId()).isEqualTo(revisionId);
            assertThat(entry.chunk().parentChunkId()).isNull();
        });
    }

    /** 验证显式传递 mode 为 null 或 FLAT 时均按平铺模式构建。 */
    @Test
    void explicitNullModeAndFlatModeJsonRequestsSuccessfullyBuildAsFlat() throws Exception {
        String[] requestBodies = new String[] {
                "{}",
                "{\"mode\":null}",
                "{\"mode\":\"FLAT\"}"
        };

        for (String body : requestBodies) {
            String fileId = uploadFile("flat-body.txt", "text/plain", "短正文内容段落。".getBytes(StandardCharsets.UTF_8));
            String baseId = createKnowledgeBase("显式平铺测试库");
            String documentId = registerDocument(baseId, fileId);

            mvc.perform(post("/api/v1/knowledge-documents/{documentId}/builds", documentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.documentId").value(documentId))
                    .andExpect(jsonPath("$.revisionId").isNotEmpty())
                    .andExpect(jsonPath("$.oldRevisionCleanupCompleted").value(true));
        }
    }

    /** 验证 PARENT_CHILD 模式生成两层父子关系，且仅检索块（RETRIEVAL）写入向量库。 */
    @Test
    void parentChildModeCreatesHierarchyAndEmbedsOnlyRetrievalChunks() throws Exception {
        String paragraph = "父子切分测试长段落，用于验证大上下文父块与检索子块的划分与关联。".repeat(6);
        String fileContent = paragraph + "\n\n" + paragraph;
        String fileId = uploadFile("parent-child.txt", "text/plain", fileContent.getBytes(StandardCharsets.UTF_8));
        String baseId = createKnowledgeBase("父子模式测试库");
        String documentId = registerDocument(baseId, fileId);

        var buildResult = mvc.perform(post("/api/v1/knowledge-documents/{documentId}/builds", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"PARENT_CHILD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(documentId))
                .andExpect(jsonPath("$.revisionId").isNotEmpty())
                .andExpect(jsonPath("$.oldRevisionCleanupCompleted").value(true))
                .andReturn();

        String responseBody = buildResult.getResponse().getContentAsString();
        String revisionId = JsonPath.read(responseBody, "$.revisionId");
        int chunkCount = JsonPath.read(responseBody, "$.chunkCount");
        int retrievalChunkCount = JsonPath.read(responseBody, "$.retrievalChunkCount");

        assertThat(chunkCount).isGreaterThan(retrievalChunkCount);
        assertThat(retrievalChunkCount).isGreaterThan(0);

        var chunkListResult = mvc.perform(get("/api/v1/knowledge-revisions/{revisionId}/chunks", revisionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(chunkCount))
                .andReturn();

        List<String> roles = JsonPath.read(chunkListResult.getResponse().getContentAsString(), "$[*].chunkRole");
        List<String> parentIds = JsonPath.read(chunkListResult.getResponse().getContentAsString(), "$[*].parentChunkId");

        assertThat(roles).contains("CONTEXT", "RETRIEVAL");
        assertThat(parentIds).filteredOn(id -> id != null && !id.isBlank()).isNotEmpty();

        assertThat(fixture.entries).hasSize(retrievalChunkCount);
        assertThat(fixture.entries).allSatisfy(entry -> {
            assertThat(entry.chunk().chunkRole()).isEqualTo(ChunkRole.RETRIEVAL);
            assertThat(entry.chunk().revisionId()).isEqualTo(revisionId);
        });
        assertThat(fixture.entries).noneMatch(entry -> entry.chunk().chunkRole() == ChunkRole.CONTEXT);
    }

    /** 验证二次重建在 VECTOR_WRITE 失败时返回 502 脱敏错误，并保留旧活动版本与旧向量。 */
    @Test
    void rebuildFailureAtVectorWritePreservesOldActiveRevisionAndVectorsReturning502() throws Exception {
        String fileContent = "用于初次构建与二次重建的源文本内容。\n\n包含两个段落。";
        String fileId = uploadFile("rebuild-sample.txt", "text/plain", fileContent.getBytes(StandardCharsets.UTF_8));
        String baseId = createKnowledgeBase("重建失败保护测试库");
        String documentId = registerDocument(baseId, fileId);

        var initialResult = mvc.perform(post("/api/v1/knowledge-documents/{documentId}/builds", documentId))
                .andExpect(status().isOk())
                .andReturn();
        String initialRevisionId = JsonPath.read(initialResult.getResponse().getContentAsString(), "$.revisionId");
        List<KnowledgeVectorEntry> initialVectors = List.copyOf(fixture.entries);
        assertThat(initialVectors).isNotEmpty();

        fixture.failWrite = true;

        var failureResponse = mvc.perform(post("/api/v1/knowledge-documents/{documentId}/builds", documentId))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VECTOR_WRITE_FAILED"))
                .andExpect(jsonPath("$.stage").value("VECTOR_WRITE"))
                .andExpect(jsonPath("$.detail").value("检索片段向量写入失败"))
                .andExpect(jsonPath("$.cleanupCompleted").value(true))
                .andReturn();

        String errorJson = failureResponse.getResponse().getContentAsString();
        assertThat(errorJson)
                .doesNotContain("模拟外部向量数据库写入失败", "IllegalStateException", "cause", "stackTrace");

        mvc.perform(get("/api/v1/knowledge-documents/{documentId}", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(documentId))
                .andExpect(jsonPath("$.indexStatus").value("indexed"))
                .andExpect(jsonPath("$.activeRevisionId").value(initialRevisionId))
                .andExpect(jsonPath("$.lastFailureCode").value("VECTOR_WRITE_FAILED"))
                .andExpect(jsonPath("$.lastFailureMessage").value("检索片段向量写入失败"));

        mvc.perform(get("/api/v1/knowledge-documents/{documentId}/revisions", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(initialRevisionId))
                .andExpect(jsonPath("$[0].revisionStatus").value("ready"))
                .andExpect(jsonPath("$[1].revisionStatus").value("failed"))
                .andExpect(jsonPath("$[1].failureStage").value("VECTOR_WRITE"))
                .andExpect(jsonPath("$[1].failureCode").value("VECTOR_WRITE_FAILED"))
                .andExpect(jsonPath("$[1].failureMessage").value("检索片段向量写入失败"));

        assertThat(fixture.entries).isEqualTo(initialVectors);
    }

    /** 旧向量删除失败时，新版本仍发布成功，响应明确返回清理未完成。 */
    @Test
    void oldVectorCleanupFailureDoesNotUndoSuccessfulPublication() throws Exception {
        String fileId = uploadFile("cleanup.txt", "text/plain", "清理失败不影响新版本发布。".getBytes(StandardCharsets.UTF_8));
        String documentId = registerDocument(createKnowledgeBase("清理边界"), fileId);
        var first = mvc.perform(post("/api/v1/knowledge-documents/{id}/builds", documentId))
                .andExpect(status().isOk()).andReturn();
        String oldId = JsonPath.read(first.getResponse().getContentAsString(), "$.revisionId");
        fixture.failDelete = true;
        var second = mvc.perform(post("/api/v1/knowledge-documents/{id}/builds", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oldRevisionCleanupCompleted").value(false)).andReturn();
        String newId = JsonPath.read(second.getResponse().getContentAsString(), "$.revisionId");
        mvc.perform(get("/api/v1/knowledge-documents/{id}", documentId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activeRevisionId").value(newId));
        mvc.perform(get("/api/v1/knowledge-revisions/{id}", oldId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revisionStatus").value("superseded"))
                .andExpect(jsonPath("$.failureCode").value("VECTOR_CLEANUP_FAILED"))
                .andExpect(jsonPath("$.cleanedAt").doesNotExist());
        assertThat(fixture.deletedRevisionIds).contains(oldId);
        assertThat(fixture.entries).anyMatch(entry -> entry.chunk().revisionId().equals(oldId))
                .anyMatch(entry -> entry.chunk().revisionId().equals(newId));
    }

    /** 构建重新校验源文件归属，登记之后文件变为不可访问时不创建新版本。 */
    @Test
    void buildRechecksFileOwnershipBeforeCreatingRevision() throws Exception {
        String fileId = uploadFile("ownership.txt", "text/plain", "归属检查。".getBytes(StandardCharsets.UTF_8));
        String documentId = registerDocument(createKnowledgeBase("文件归属"), fileId);
        jdbc.update("UPDATE file_asset SET create_by=? WHERE id=?", "other-user", fileId);
        mvc.perform(post("/api/v1/knowledge-documents/{id}/builds", documentId))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/knowledge-documents/{id}/revisions", documentId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    /** 验证空白文本在 PARSE 阶段失败返回 422，文档标记 failed 并可查到失败版本。 */
    @Test
    void blankTextFailsAtParseStageReturning422AndFailedRevisionQueryable() throws Exception {
        String blankContent = "   \n\n  \t  \r\n   ";
        String fileId = uploadFile("blank.txt", "text/plain", blankContent.getBytes(StandardCharsets.UTF_8));
        String baseId = createKnowledgeBase("空白文档测试库");
        String documentId = registerDocument(baseId, fileId);

        var result = mvc.perform(post("/api/v1/knowledge-documents/{documentId}/builds", documentId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("PARSE_FAILED"))
                .andExpect(jsonPath("$.stage").value("PARSE"))
                .andExpect(jsonPath("$.detail").value("源文件解析失败"))
                .andExpect(jsonPath("$.cleanupCompleted").value(true))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("文档内容不能为空白", "IllegalArgumentException", "cause", "stackTrace");

        mvc.perform(get("/api/v1/knowledge-documents/{documentId}", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(documentId))
                .andExpect(jsonPath("$.indexStatus").value("failed"))
                .andExpect(jsonPath("$.activeRevisionId").doesNotExist())
                .andExpect(jsonPath("$.lastFailureCode").value("PARSE_FAILED"))
                .andExpect(jsonPath("$.lastFailureMessage").value("源文件解析失败"));

        mvc.perform(get("/api/v1/knowledge-documents/{documentId}/revisions", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].revisionStatus").value("failed"))
                .andExpect(jsonPath("$[0].failureStage").value("PARSE"))
                .andExpect(jsonPath("$[0].failureCode").value("PARSE_FAILED"))
                .andExpect(jsonPath("$[0].failureMessage").value("源文件解析失败"));
    }

    /** 验证坏 UTF-8 字节在 PARSE 阶段被严格拦截并返回 422。 */
    @Test
    void illegalUtf8BytesFailAtParseStageReturning422() throws Exception {
        byte[] invalidUtf8 = new byte[] {(byte) 0xC0, (byte) 0xAF, 0x20, 0x41};
        String fileId = uploadFile("bad-utf8.txt", "text/plain", invalidUtf8);
        String baseId = createKnowledgeBase("坏编码测试库");
        String documentId = registerDocument(baseId, fileId);

        mvc.perform(post("/api/v1/knowledge-documents/{documentId}/builds", documentId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PARSE_FAILED"))
                .andExpect(jsonPath("$.stage").value("PARSE"))
                .andExpect(jsonPath("$.detail").value("源文件解析失败"));

        mvc.perform(get("/api/v1/knowledge-documents/{documentId}", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexStatus").value("failed"))
                .andExpect(jsonPath("$.lastFailureCode").value("PARSE_FAILED"));
    }

    /** 验证提交非法 mode JSON 时返回 400，且不创建新版本。 */
    @Test
    void illegalModeJsonReturns400BadRequestAndCreatesNoRevision() throws Exception {
        String fileId = uploadFile("valid.txt", "text/plain", "合法正文内容。".getBytes(StandardCharsets.UTF_8));
        String baseId = createKnowledgeBase("非法模式测试库");
        String documentId = registerDocument(baseId, fileId);

        mvc.perform(post("/api/v1/knowledge-documents/{documentId}/builds", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"ILLEGAL_MODE_NAME\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"))
                .andExpect(jsonPath("$.detail").value("请求体格式不正确"));

        mvc.perform(get("/api/v1/knowledge-documents/{documentId}/revisions", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get("/api/v1/knowledge-documents/{documentId}", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexStatus").value("pending"));
    }

    /** 验证文档已有 building 版本时并发构建返回 409 冲突。 */
    @Test
    void concurrentBuildingReturns409Conflict() throws Exception {
        String fileId = uploadFile("conflict.txt", "text/plain", "用于测试冲突的文本。".getBytes(StandardCharsets.UTF_8));
        String baseId = createKnowledgeBase("构建冲突测试库");
        String documentId = registerDocument(baseId, fileId);

        revisionService.create(documentId);

        mvc.perform(post("/api/v1/knowledge-documents/{documentId}/builds", documentId))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("BUILD_ALREADY_RUNNING"))
                .andExpect(jsonPath("$.title").value("知识库操作冲突"))
                .andExpect(jsonPath("$.detail").value("该文档已有构建正在进行"));
    }

    /** 验证源文件缺少已注册解析策略时返回 503，且不创建新版本。 */
    @Test
    void unregisteredPdfFormatStrategyReturns503AndCreatesNoRevision() throws Exception {
        byte[] dummyPdfBytes = "%PDF-1.4 示例 PDF 二进制伪内容".getBytes(StandardCharsets.UTF_8);
        String fileId = uploadFile("sample.pdf", "application/pdf", dummyPdfBytes);
        String baseId = createKnowledgeBase("未注册策略测试库");
        String documentId = registerDocument(baseId, fileId);

        mvc.perform(post("/api/v1/knowledge-documents/{documentId}/builds", documentId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_PARSER_UNAVAILABLE"))
                .andExpect(jsonPath("$.title").value("知识库构建不可用"))
                .andExpect(jsonPath("$.detail").value("源格式没有已注册的解析策略"));

        mvc.perform(get("/api/v1/knowledge-documents/{documentId}/revisions", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** 验证操作不存在的文档、版本或切片资源时统一返回 404 RESOURCE_NOT_FOUND。 */
    @Test
    void nonExistentResourcesReturn404NotFound() throws Exception {
        String nonExistentId = "non-existent-resource-id";

        mvc.perform(post("/api/v1/knowledge-documents/{documentId}/builds", nonExistentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("资源不存在"));

        mvc.perform(get("/api/v1/knowledge-documents/{documentId}", nonExistentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mvc.perform(get("/api/v1/knowledge-revisions/{revisionId}", nonExistentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mvc.perform(get("/api/v1/knowledge-chunks/{chunkId}", nonExistentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /** 辅助方法：通过 MVC 上传文件并返回文件业务 ID。 */
    private String uploadFile(String filename, String mime, byte[] content) throws Exception {
        var result = mvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile("file", filename, mime, content))
                        .param("storageType", "minio"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    /** 辅助方法：通过 HTTP 接口创建知识库元数据并返回其业务 ID。 */
    private String createKnowledgeBase(String name) throws Exception {
        var result = mvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    /** 辅助方法：通过 HTTP 接口将文件登记到指定知识库并返回文档业务 ID。 */
    private String registerDocument(String baseId, String fileId) throws Exception {
        var result = mvc.perform(post("/api/v1/knowledge-bases/{baseId}/documents", baseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"" + fileId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    /** 测试专属 Bean 配置类：装配内存替身与本地真实策略构建服务。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class KnowledgeBuildTestConfiguration {

        @Bean
        VectorIndexFixture vectorIndexFixture() {
            return new VectorIndexFixture();
        }

        @Bean
        KnowledgeBuildLifecycleService knowledgeBuildLifecycleService(
                KnowledgeDocumentRepository documents,
                KnowledgeRevisionRepository revisions,
                KnowledgeChunkRepository chunks,
                Clock clock) {
            return new KnowledgeBuildLifecycleService(documents, revisions, chunks, clock);
        }

        @Bean
        KnowledgeIndexBuildService knowledgeIndexBuildService(
                FileContentService files,
                KnowledgeDocumentRepository documents,
                KnowledgeChunkService chunks,
                KnowledgeBuildLifecycleService lifecycle,
                VectorIndexFixture fixture) {
            var parsers = List.<KnowledgeDocumentParser>of(new TextKnowledgeDocumentParser(1_000_000));
            var chunkers = List.<KnowledgeChunker>of(
                    new TokenBudgetKnowledgeChunker(ChunkingMode.FLAT),
                    new TokenBudgetKnowledgeChunker(ChunkingMode.PARENT_CHILD));
            var router = new KnowledgeProcessingRouter(parsers, chunkers);
            var sourceReader = new KnowledgeSourceReader(1_000_000);
            var writer = new KnowledgeChunkGroupWriter(chunks);
            var options = new ChunkingOptions(20, 0, 0, 1000, true, ".。?!\n", 80, 3);
            int batchSize = 3;
            return new KnowledgeIndexBuildService(
                    files,
                    documents,
                    router,
                    sourceReader,
                    writer,
                    fixture,
                    lifecycle,
                    options,
                    batchSize);
        }
    }

    /** 外部向量索引客户端的内存测试替身。 */
    static final class VectorIndexFixture implements KnowledgeVectorIndex {
        final List<KnowledgeVectorEntry> entries = new ArrayList<>();
        final List<String> deletedRevisionIds = new ArrayList<>();
        boolean failWrite;
        boolean failDelete;

        void reset() {
            entries.clear();
            deletedRevisionIds.clear();
            failWrite = false;
            failDelete = false;
        }

        @Override
        public void add(List<KnowledgeVectorEntry> newEntries) {
            if (failWrite) {
                throw new IllegalStateException("模拟外部向量数据库写入失败");
            }
            if (newEntries != null) {
                entries.addAll(newEntries);
            }
        }

        @Override
        public void deleteByRevisionId(String revisionId) {
            deletedRevisionIds.add(revisionId);
            if (failDelete) {
                throw new IllegalStateException("模拟外部向量数据库删除失败");
            }
            entries.removeIf(entry -> entry.chunk().revisionId().equals(revisionId));
        }
    }
}
