package com.item.aisummary.web.knowledge.controller;

import com.item.aisummary.application.file.port.FileRepository;
import com.item.aisummary.application.knowledge.command.SaveKnowledgeChunkCommand;
import com.item.aisummary.application.knowledge.service.KnowledgeChunkService;
import com.item.aisummary.application.knowledge.service.KnowledgeRevisionService;
import com.item.aisummary.domain.file.model.FileAsset;
import com.item.aisummary.domain.knowledge.enums.ChunkRole;
import com.item.aisummary.domain.knowledge.value.Heading;
import com.item.aisummary.infrastructure.web.RequestIdFilter;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesRegex;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 知识库模块 HTTP 端到端行为测试。
 * 使用 MockMvc 与隔离 H2 验证知识库、文档登记、构建 503 熔断以及版本与切片的只读契约。
 * 默认使用模拟用户身份，无外层事务，仅通过 @BeforeEach 清理知识库四表与文件元数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeHttpTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FileRepository fileRepository;
    @Autowired private KnowledgeRevisionService revisionService;
    @Autowired private KnowledgeChunkService chunkService;

    /** 每次测试前清理知识库四表与文件元数据，避免测试间状态污染。 */
    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM knowledge_chunk");
        jdbc.update("DELETE FROM knowledge_document_revision");
        jdbc.update("DELETE FROM knowledge_document");
        jdbc.update("DELETE FROM knowledge_base");
        jdbc.update("DELETE FROM file_asset");
    }

    /** 创建知识库返回 201、Location 头及严格五字段，服务层裁剪名称首尾空白。 */
    @Test
    void createsKnowledgeBaseWithLocationAndStrictPublicFields() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"  技术架构学习库  \"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesRegex("^/api/v1/knowledge-bases/[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("技术架构学习库"))
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.createAt").isString()).andExpect(jsonPath("$.updateAt").isString())
                .andReturn();

        Map<?, ?> response = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(response.keySet().stream().map(Object::toString).toList())
                .containsExactlyInAnyOrder("id", "name", "status", "createAt", "updateAt");
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/knowledge-bases/" + response.get("id"));
        assertThat(response.get("createAt")).isEqualTo(response.get("updateAt"));
    }

    /** 允许多个知识库同名，系统各自分配独立业务 ID。 */
    @Test
    void allowsDuplicateKnowledgeBaseNames() throws Exception {
        String first = createKnowledgeBase("公共文档库"), second = createKnowledgeBase("公共文档库");
        assertThat(first).isNotEqualTo(second);
        mvc.perform(get("/api/v1/knowledge-bases")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("公共文档库")).andExpect(jsonPath("$[1].name").value("公共文档库"));
    }

    /** 按 ID 查询知识库支持路径首尾空格裁剪，查不存在库返回 404。 */
    @Test
    void findsKnowledgeBaseByIdAndHandlesNonExistent() throws Exception {
        String id = createKnowledgeBase("分布式系统原理");
        mvc.perform(get("/api/v1/knowledge-bases/{baseId}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.name").value("分布式系统原理"));
        mvc.perform(get("/api/v1/knowledge-bases/{baseId}", "  " + id + "  ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/v1/knowledge-bases/{baseId}", UUID.randomUUID().toString().replace("-", "")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /** 重命名成功更新名称并刷新 updateAt，保留原创建时间与状态；修改不存在的库返回 404。 */
    @Test
    void renamesKnowledgeBaseSuccessfully() throws Exception {
        String id = createKnowledgeBase("原知识库名称");
        MvcResult original = mvc.perform(get("/api/v1/knowledge-bases/{baseId}", id)).andExpect(status().isOk()).andReturn();
        String originalCreated = JsonPath.read(original.getResponse().getContentAsString(), "$.createAt");
        String originalUpdated = JsonPath.read(original.getResponse().getContentAsString(), "$.updateAt");

        MvcResult renamed = mvc.perform(put("/api/v1/knowledge-bases/{baseId}", id).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"  新知识库名称  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("新知识库名称")).andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.createAt").value(originalCreated)).andReturn();

        String renamedUpdated = JsonPath.read(renamed.getResponse().getContentAsString(), "$.updateAt");
        assertThat(Instant.parse(renamedUpdated)).isAfterOrEqualTo(Instant.parse(originalUpdated));
        mvc.perform(put("/api/v1/knowledge-bases/{baseId}", UUID.randomUUID().toString().replace("-", ""))
                        .contentType(APPLICATION_JSON).content("{\"name\":\"任意名称\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /** 知识库名称非法（空白或超过 100 字符）拒绝并返回 400 REQUEST_VALIDATION_ERROR。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "12345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901"})
    void rejectsInvalidKnowledgeBaseName(String name) throws Exception {
        mvc.perform(post("/api/v1/knowledge-bases").contentType(APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_ERROR"));
    }

    /** 畸形或不可读 JSON 请求体返回 400 REQUEST_BODY_INVALID。 */
    @Test
    void rejectsMalformedJsonBody() throws Exception {
        mvc.perform(post("/api/v1/knowledge-bases").contentType(APPLICATION_JSON).content("{invalid-json}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));
    }

    /** 路径 ID 校验：全空白或超过 32 字符被拒绝并返回 400 REQUEST_VALIDATION_ERROR。 */
    @Test
    void rejectsBlankOrOverlongPathId() throws Exception {
        for (String path : List.of("   ", "123456789012345678901234567890123")) {
            mvc.perform(get("/api/v1/knowledge-bases/{baseId}", path))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_ERROR"));
        }
    }

    /** 登记源文件返回 201、Location 头及十个领域字段，初始状态固定为 pending。 */
    @Test
    void registersDocumentWithLocationAndStrictPublicFields() throws Exception {
        String baseId = createKnowledgeBase("AI 前沿论文库");
        String fileId = insertFileRecord("file-asset-01", "mock-user", 1, "completed", null);

        MvcResult result = mvc.perform(post("/api/v1/knowledge-bases/{baseId}/documents", baseId)
                        .contentType(APPLICATION_JSON).content("{\"fileId\":\"" + fileId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesRegex("^/api/v1/knowledge-documents/[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.id").isNotEmpty()).andExpect(jsonPath("$.knowledgeBaseId").value(baseId))
                .andExpect(jsonPath("$.fileId").value(fileId)).andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.indexStatus").value("pending"))
                .andExpect(jsonPath("$.activeRevisionId").doesNotExist()).andExpect(jsonPath("$.lastFailureCode").doesNotExist())
                .andReturn();

        Map<?, ?> response = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(response.keySet().stream().map(Object::toString).toList())
                .containsExactlyInAnyOrder("id", "knowledgeBaseId", "fileId", "status", "indexStatus",
                        "activeRevisionId", "lastFailureCode", "lastFailureMessage", "createAt", "updateAt");
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/knowledge-documents/" + response.get("id"));
    }

    /** 源文件非当前所有者、未完成、已删除、已停用、缺失文件记录或不存在知识库均统一返回 404。 */
    @Test
    void rejectsDocumentRegistrationForInaccessibleOrMissingFile() throws Exception {
        String baseId = createKnowledgeBase("安全隔离测试库");
        List<String> invalidFiles = List.of(
                insertFileRecord("file-other-user", "attacker-user", 1, "completed", null),
                insertFileRecord("file-pending", "mock-user", 1, "pending", null),
                insertFileRecord("file-deleted", "mock-user", 1, "completed", Instant.now()),
                insertFileRecord("file-disabled", "mock-user", 0, "completed", null),
                UUID.randomUUID().toString().replace("-", ""));

        for (String fileId : invalidFiles) {
            mvc.perform(post("/api/v1/knowledge-bases/{baseId}/documents", baseId)
                            .contentType(APPLICATION_JSON).content("{\"fileId\":\"" + fileId + "\"}"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }

        String validFile = insertFileRecord("file-valid", "mock-user", 1, "completed", null);
        mvc.perform(post("/api/v1/knowledge-bases/{baseId}/documents", UUID.randomUUID().toString().replace("-", ""))
                        .contentType(APPLICATION_JSON).content("{\"fileId\":\"" + validFile + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /** 同一文件重复登记到同库返回 409 DOCUMENT_ALREADY_REGISTERED 并回显关联 requestId。 */
    @Test
    void rejectsDuplicateDocumentRegistrationWithConflictAndRequestId() throws Exception {
        String baseId = createKnowledgeBase("防重校验知识库");
        String fileId = insertFileRecord("file-asset-dup", "mock-user", 1, "completed", null);
        mvc.perform(post("/api/v1/knowledge-bases/{baseId}/documents", baseId)
                        .contentType(APPLICATION_JSON).content("{\"fileId\":\"" + fileId + "\"}"))
                .andExpect(status().isCreated());

        String customRequestId = "req-conflict-check-999";
        mvc.perform(post("/api/v1/knowledge-bases/{baseId}/documents", baseId)
                        .contentType(APPLICATION_JSON).header(RequestIdFilter.HEADER_NAME, customRequestId)
                        .content("{\"fileId\":\"" + fileId + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, customRequestId))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("DOCUMENT_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.title").value("知识库操作冲突"))
                .andExpect(jsonPath("$.requestId").value(customRequestId))
                .andExpect(jsonPath("$.instance").value("/api/v1/knowledge-bases/" + baseId + "/documents"));
    }

    /** 校验登记文档请求体参数：fileId 不能为空白且不超过 32 字符。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "123456789012345678901234567890123"})
    void rejectsInvalidFileIdOnDocumentRegistration(String fileId) throws Exception {
        String baseId = createKnowledgeBase("参数校验库");
        mvc.perform(post("/api/v1/knowledge-bases/{baseId}/documents", baseId)
                        .contentType(APPLICATION_JSON).content("{\"fileId\":\"" + fileId + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_ERROR"));
    }

    /** 查询知识库文档列表：空库返回空列表 []，父库不存在返回 404。 */
    @Test
    void returnsEmptyListForEmptyBaseAnd404ForMissingBase() throws Exception {
        String baseId = createKnowledgeBase("空文档知识库");
        mvc.perform(get("/api/v1/knowledge-bases/{baseId}/documents", baseId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        String fileId = insertFileRecord("file-asset-list-01", "mock-user", 1, "completed", null);
        registerDocument(baseId, fileId);

        mvc.perform(get("/api/v1/knowledge-bases/{baseId}/documents", baseId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fileId").value(fileId));

        mvc.perform(get("/api/v1/knowledge-bases/{baseId}/documents", UUID.randomUUID().toString().replace("-", "")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /** 查询单文档详情返回 200，查询不存在文档返回 404。 */
    @Test
    void findsDocumentByIdSuccessfully() throws Exception {
        String baseId = createKnowledgeBase("详情查询库");
        String fileId = insertFileRecord("file-asset-detail", "mock-user", 1, "completed", null);
        String docId = registerDocument(baseId, fileId);

        mvc.perform(get("/api/v1/knowledge-documents/{documentId}", docId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(docId))
                .andExpect(jsonPath("$.knowledgeBaseId").value(baseId)).andExpect(jsonPath("$.fileId").value(fileId))
                .andExpect(jsonPath("$.status").value(1)).andExpect(jsonPath("$.indexStatus").value("pending"));

        mvc.perform(get("/api/v1/knowledge-documents/{documentId}", UUID.randomUUID().toString().replace("-", "")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /** RAG 关闭时在文档存在性校验后才报 503，文档不存在先报 404，拒绝后数据库无版本脏写入。 */
    @Test
    void returns503WhenRagDisabledOnlyAfterVerifyingIdentityAndDocument() throws Exception {
        String baseId = createKnowledgeBase("构建校验库");
        String fileId = insertFileRecord("file-asset-build", "mock-user", 1, "completed", null);
        String docId = registerDocument(baseId, fileId);

        mvc.perform(post("/api/v1/knowledge-documents/{id}/builds", UUID.randomUUID().toString().replace("-", ""))
                        .contentType(APPLICATION_JSON).content("{\"mode\":\"FLAT\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mvc.perform(post("/api/v1/knowledge-documents/{id}/builds", docId).contentType(APPLICATION_JSON).content("{\"mode\":\"FLAT\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("KNOWLEDGE_RAG_DISABLED"))
                .andExpect(jsonPath("$.title").value("知识库构建不可用"));

        mvc.perform(post("/api/v1/knowledge-documents/{id}/builds", docId))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("KNOWLEDGE_RAG_DISABLED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_document_revision WHERE knowledge_document_id=?",
                Integer.class, docId)).isZero();
    }

    /** 版本与切片列表空集合返回 []，父级不存在统一返回 404。 */
    @Test
    void returnsEmptyListForParentWithoutChildrenAnd404ForMissingParent() throws Exception {
        String baseId = createKnowledgeBase("层级空集合库");
        String fileId = insertFileRecord("file-asset-rev-chunk", "mock-user", 1, "completed", null);
        String docId = registerDocument(baseId, fileId);
        String missing = UUID.randomUUID().toString().replace("-", "");

        mvc.perform(get("/api/v1/knowledge-documents/{id}/revisions", docId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/knowledge-documents/{id}/revisions", missing))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        var rev1 = revisionService.create(docId);
        mvc.perform(get("/api/v1/knowledge-revisions/{id}/chunks", rev1.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/knowledge-revisions/{id}/chunks", missing))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/knowledge-chunks/{id}", missing))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /** 版本列表按 revisionNumber 严格升序排序，详情包含 14 个版本元数据与生命周期字段。 */
    @Test
    void queriesRevisionsAndPreservesAscendingNumberOrder() throws Exception {
        String baseId = createKnowledgeBase("版本排序测试库");
        String fileId = insertFileRecord("file-asset-rev-sort", "mock-user", 1, "completed", null);
        String docId = registerDocument(baseId, fileId);

        var rev1 = revisionService.create(docId);
        var rev2 = revisionService.create(docId);
        assertThat(rev1.revisionNumber()).isEqualTo(1);
        assertThat(rev2.revisionNumber()).isEqualTo(2);

        mvc.perform(get("/api/v1/knowledge-documents/{id}/revisions", docId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(rev1.id())).andExpect(jsonPath("$[0].revisionNumber").value(1))
                .andExpect(jsonPath("$[1].id").value(rev2.id())).andExpect(jsonPath("$[1].revisionNumber").value(2));

        MvcResult result = mvc.perform(get("/api/v1/knowledge-revisions/{id}", rev1.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revisionStatus").value("building"))
                .andExpect(jsonPath("$.chunkCount").value(0)).andReturn();

        Map<?, ?> response = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(response.keySet().stream().map(Object::toString).toList())
                .containsExactlyInAnyOrder("id", "knowledgeDocumentId", "revisionNumber", "revisionStatus",
                        "sourceSha256", "chunkCount", "failureStage", "failureCode", "failureMessage",
                        "createdAt", "startedAt", "completedAt", "publishedAt", "cleanedAt");
    }

    /** 验证片段按序号升序、角色区分、两层父子指向、标题 null 与 [] 区别、未知坐标保留 null。 */
    @Test
    void queriesChunksAndValidatesRolesParentChildHeadingAndNullLocations() throws Exception {
        String baseId = createKnowledgeBase("片段语义测试库");
        String fileId = insertFileRecord("file-asset-chunk-semantic", "mock-user", 1, "completed", null);
        String docId = registerDocument(baseId, fileId);
        var revision = revisionService.create(docId);

        var commands = List.of(
                new SaveKnowledgeChunkCommand(null, ChunkRole.CONTEXT, 0, "第一章 系统架构总览说明文本",
                        List.of(new Heading(1, "第一章 系统架构")), 45, 0, 100, 1, 1),
                new SaveKnowledgeChunkCommand(0, ChunkRole.RETRIEVAL, 1, "系统核心模块包含网关与数据接入组件",
                        List.of(), 20, 0, 40, 1, 1),
                new SaveKnowledgeChunkCommand(null, ChunkRole.RETRIEVAL, 2, "独立检索片段：由于特殊排版未映射全文坐标",
                        null, null, null, null, null, null));

        var saved = chunkService.saveBatch(revision.id(), commands);
        String c0 = saved.get(0).id(), c1 = saved.get(1).id(), c2 = saved.get(2).id();

        mvc.perform(get("/api/v1/knowledge-revisions/{id}/chunks", revision.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(c0)).andExpect(jsonPath("$[0].chunkRole").value("CONTEXT"))
                .andExpect(jsonPath("$[0].sequenceNo").value(0)).andExpect(jsonPath("$[0].parentChunkId").doesNotExist())
                .andExpect(jsonPath("$[0].headingPath[0].level").value(1)).andExpect(jsonPath("$[0].headingPath[0].title").value("第一章 系统架构"))
                .andExpect(jsonPath("$[0].tokenCount").value(45))
                .andExpect(jsonPath("$[0].startOffset").value(0)).andExpect(jsonPath("$[0].endOffset").value(100))
                .andExpect(jsonPath("$[0].pageStart").value(1)).andExpect(jsonPath("$[0].pageEnd").value(1))
                .andExpect(jsonPath("$[1].id").value(c1)).andExpect(jsonPath("$[1].chunkRole").value("RETRIEVAL"))
                .andExpect(jsonPath("$[1].sequenceNo").value(1)).andExpect(jsonPath("$[1].parentChunkId").value(c0))
                .andExpect(jsonPath("$[1].headingPath").isArray()).andExpect(jsonPath("$[1].headingPath.length()").value(0))
                .andExpect(jsonPath("$[1].startOffset").value(0)).andExpect(jsonPath("$[1].endOffset").value(40))
                .andExpect(jsonPath("$[2].id").value(c2)).andExpect(jsonPath("$[2].chunkRole").value("RETRIEVAL"))
                .andExpect(jsonPath("$[2].sequenceNo").value(2)).andExpect(jsonPath("$[2].parentChunkId").doesNotExist())
                .andExpect(jsonPath("$[2].headingPath").doesNotExist()).andExpect(jsonPath("$[2].tokenCount").doesNotExist())
                .andExpect(jsonPath("$[2].startOffset").doesNotExist()).andExpect(jsonPath("$[2].endOffset").doesNotExist());

        MvcResult childDetail = mvc.perform(get("/api/v1/knowledge-chunks/{id}", c1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(c1))
                .andExpect(jsonPath("$.parentChunkId").value(c0)).andExpect(jsonPath("$.chunkRole").value("RETRIEVAL"))
                .andExpect(jsonPath("$.content").value("系统核心模块包含网关与数据接入组件")).andReturn();

        Map<?, ?> childMap = json.readValue(childDetail.getResponse().getContentAsString(), Map.class);
        assertThat(childMap.keySet().stream().map(Object::toString).toList())
                .containsExactlyInAnyOrder("id", "revisionId", "parentChunkId", "chunkRole", "sequenceNo",
                        "content", "headingPath", "tokenCount", "startOffset", "endOffset", "pageStart", "pageEnd", "createAt");

        mvc.perform(get("/api/v1/knowledge-revisions/{id}", revision.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.chunkCount").value(3));
    }

    /** 版本与切片禁止 POST 写入，四层资源均未提供 DELETE 删除接口（均返回 405）。 */
    @Test
    void rejectsMutationsOnReadOnlyRevisionAndChunkEndpointsAndUnsupportedDeletes() throws Exception {
        String baseId = createKnowledgeBase("只读限制测试库");
        String fileId = insertFileRecord("file-asset-readonly", "mock-user", 1, "completed", null);
        String docId = registerDocument(baseId, fileId);
        var rev = revisionService.create(docId);

        for (var request : List.of(
                post("/api/v1/knowledge-documents/{id}/revisions", docId).contentType(APPLICATION_JSON).content("{}"),
                post("/api/v1/knowledge-revisions/{id}/chunks", rev.id()).contentType(APPLICATION_JSON).content("{}"),
                delete("/api/v1/knowledge-bases/{id}", baseId),
                delete("/api/v1/knowledge-documents/{id}", docId),
                delete("/api/v1/knowledge-revisions/{id}", rev.id()),
                delete("/api/v1/knowledge-chunks/{id}", "chunk-id"))) {
            mvc.perform(request).andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        }
    }

    /** 辅助方法：通过 HTTP API 创建一个知识库并返回其业务 ID。 */
    private String createKnowledgeBase(String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    /** 辅助方法：通过 HTTP API 为知识库登记源文件并返回文档 ID。 */
    private String registerDocument(String baseId, String fileId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/knowledge-bases/{baseId}/documents", baseId)
                        .contentType(APPLICATION_JSON).content("{\"fileId\":\"" + fileId + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    /** 辅助方法：向 file_asset 表插入文件元数据记录，用于校验文件所有权与状态。 */
    private String insertFileRecord(
            String id, String createBy, int status, String uploadStatus, Instant deletedAt) {
        Instant now = Instant.now();
        fileRepository.insert(new FileAsset(
                id, "fixture-" + id + ".txt", "text/plain", 1024L, "minio",
                "files/fixture/" + id + ".txt", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                status, deletedAt, uploadStatus, createBy, createBy, now, now));
        return id;
    }
}
