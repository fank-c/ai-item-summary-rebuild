package com.item.aisummary.web.knowledge.controller;

import com.item.aisummary.application.knowledge.service.KnowledgeRevisionService;
import com.item.aisummary.infrastructure.auth.web.LoginUserContext;
import com.item.aisummary.web.knowledge.response.KnowledgeRevisionResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 版本只读 HTTP 入口；版本创建与状态流转由完整构建流程管理。 */
@RestController
@RequestMapping("/api/v1")
public class KnowledgeRevisionController {
    private final KnowledgeRevisionService revisions;

    public KnowledgeRevisionController(KnowledgeRevisionService revisions) {
        this.revisions = revisions;
    }

    /** 按版本号升序查询文档全部版本，包含构建中、失败和已替代版本。 */
    @GetMapping("/knowledge-documents/{documentId}/revisions")
    public List<KnowledgeRevisionResponse> findByDocument(
            @PathVariable String documentId, HttpServletRequest request) {
        LoginUserContext.require(request);
        return revisions.findByDocumentId(documentId).stream().map(KnowledgeRevisionResponse::from).toList();
    }

    /** 查询单个版本的计数、失败摘要与生命周期时间；不将 ready 等同于当前活动版本。 */
    @GetMapping("/knowledge-revisions/{revisionId}")
    public KnowledgeRevisionResponse findById(@PathVariable String revisionId, HttpServletRequest request) {
        LoginUserContext.require(request);
        return KnowledgeRevisionResponse.from(revisions.findById(revisionId));
    }
}
