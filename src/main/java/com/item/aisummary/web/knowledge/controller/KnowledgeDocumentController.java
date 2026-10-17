package com.item.aisummary.web.knowledge.controller;

import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildUnavailableException;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeIndexBuildService;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingMode;
import com.item.aisummary.application.knowledge.service.KnowledgeDocumentService;
import com.item.aisummary.infrastructure.auth.web.LoginUserContext;
import com.item.aisummary.web.knowledge.request.BuildKnowledgeDocumentRequest;
import com.item.aisummary.web.knowledge.request.RegisterKnowledgeDocumentRequest;
import com.item.aisummary.web.knowledge.response.KnowledgeBuildResponse;
import com.item.aisummary.web.knowledge.response.KnowledgeDocumentResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 文档登记、查询与同步构建 HTTP 入口；构建使用可选 Bean，RAG 关闭不影响元数据接口。 */
@RestController
@RequestMapping("/api/v1")
public class KnowledgeDocumentController {
    private final KnowledgeDocumentService documents;
    /** 按请求获取构建服务的 Spring 代理，允许默认关闭 RAG 时正常启动。 */
    private final ObjectProvider<KnowledgeIndexBuildService> builds;

    public KnowledgeDocumentController(KnowledgeDocumentService documents,
            ObjectProvider<KnowledgeIndexBuildService> builds) {
        this.documents = documents;
        this.builds = builds;
    }

    /** 登记调用者拥有的已上传文件，仅建立 pending 文档关系，不读取正文或构建索引。 */
    @PostMapping("/knowledge-bases/{baseId}/documents")
    public ResponseEntity<KnowledgeDocumentResponse> register(@PathVariable String baseId,
            @Valid @RequestBody RegisterKnowledgeDocumentRequest body, HttpServletRequest request) {
        var document = documents.register(LoginUserContext.require(request).id(), baseId, body.fileId());
        return ResponseEntity.created(URI.create("/api/v1/knowledge-documents/" + document.id()))
                .body(KnowledgeDocumentResponse.from(document));
    }

    /** 查询指定知识库的全部登记文档；库不存在返回 404，存在但未登记则返回空数组。 */
    @GetMapping("/knowledge-bases/{baseId}/documents")
    public List<KnowledgeDocumentResponse> findByBase(@PathVariable String baseId, HttpServletRequest request) {
        LoginUserContext.require(request);
        return documents.findByKnowledgeBaseId(baseId).stream().map(KnowledgeDocumentResponse::from).toList();
    }

    /** 查询文档状态与活动版本，不展开正文或历史版本。 */
    @GetMapping("/knowledge-documents/{documentId}")
    public KnowledgeDocumentResponse findById(@PathVariable String documentId, HttpServletRequest request) {
        LoginUserContext.require(request);
        return KnowledgeDocumentResponse.from(documents.findById(documentId));
    }

    /**
     * 同步完成新版本构建和发布后返回结果；每次成功调用创建新版本，不提供幂等或后台任务语义。
     *
     * <p>请求体可省略，默认 FLAT。构建可能执行 Embedding、Qdrant 及已启用的 PDF 云解析；
     * 响应丢失或请求超时不代表构建已撤销，应通过文档和版本查询核对结果。
     */
    @PostMapping("/knowledge-documents/{documentId}/builds")
    public KnowledgeBuildResponse build(@PathVariable String documentId,
            @RequestBody(required = false) BuildKnowledgeDocumentRequest body, HttpServletRequest request) {
        String actor = LoginUserContext.require(request).id();
        var document = documents.findById(documentId);
        var service = builds.getIfAvailable();
        if (service == null) {
            throw new KnowledgeBuildUnavailableException("KNOWLEDGE_RAG_DISABLED", "知识库构建未启用");
        }
        ChunkingMode mode = body == null ? ChunkingMode.FLAT : body.effectiveMode();
        return KnowledgeBuildResponse.from(service.build(actor, document.id(), mode));
    }
}
