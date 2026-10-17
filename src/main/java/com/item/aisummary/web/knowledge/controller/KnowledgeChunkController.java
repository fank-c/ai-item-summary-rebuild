package com.item.aisummary.web.knowledge.controller;

import com.item.aisummary.application.knowledge.service.KnowledgeChunkService;
import com.item.aisummary.infrastructure.auth.web.LoginUserContext;
import com.item.aisummary.web.knowledge.response.KnowledgeChunkResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 已持久化片段的只读 HTTP 入口，供查看构建结果；不执行向量检索或手工写入。 */
@RestController
@RequestMapping("/api/v1")
public class KnowledgeChunkController {
    private final KnowledgeChunkService chunks;

    public KnowledgeChunkController(KnowledgeChunkService chunks) {
        this.chunks = chunks;
    }

    /** 按全局序号升序返回版本的所有角色片段，未知标题和位置保留 null。 */
    @GetMapping("/knowledge-revisions/{revisionId}/chunks")
    public List<KnowledgeChunkResponse> findByRevision(@PathVariable String revisionId, HttpServletRequest request) {
        LoginUserContext.require(request);
        return chunks.findByRevisionId(revisionId).stream().map(KnowledgeChunkResponse::from).toList();
    }

    /** 查询单个片段正文、父引用与来源位置，不自动展开父块或子块。 */
    @GetMapping("/knowledge-chunks/{chunkId}")
    public KnowledgeChunkResponse findById(@PathVariable String chunkId, HttpServletRequest request) {
        LoginUserContext.require(request);
        return KnowledgeChunkResponse.from(chunks.findById(chunkId));
    }
}
