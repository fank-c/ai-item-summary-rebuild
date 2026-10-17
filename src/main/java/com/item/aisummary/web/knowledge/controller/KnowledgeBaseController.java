package com.item.aisummary.web.knowledge.controller;

import com.item.aisummary.application.knowledge.service.KnowledgeBaseService;
import com.item.aisummary.infrastructure.auth.web.LoginUserContext;
import com.item.aisummary.web.knowledge.request.SaveKnowledgeBaseRequest;
import com.item.aisummary.web.knowledge.response.KnowledgeBaseResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 知识库元数据 HTTP 入口；要求请求身份，知识库访问权限关系尚未实现。 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService bases;

    public KnowledgeBaseController(KnowledgeBaseService bases) {
        this.bases = bases;
    }

    /** 创建知识库并返回可查询的资源地址；不创建文件、文档或索引。 */
    @PostMapping
    public ResponseEntity<KnowledgeBaseResponse> create(
            @Valid @RequestBody SaveKnowledgeBaseRequest body, HttpServletRequest request) {
        LoginUserContext.require(request);
        var base = bases.create(body.name());
        return ResponseEntity.created(URI.create("/api/v1/knowledge-bases/" + base.id()))
                .body(KnowledgeBaseResponse.from(base));
    }

    /** 查询全部知识库；沿用基础服务的无分页列表，顺序不作保证。 */
    @GetMapping
    public List<KnowledgeBaseResponse> findAll(HttpServletRequest request) {
        LoginUserContext.require(request);
        return bases.findAll().stream().map(KnowledgeBaseResponse::from).toList();
    }

    /** 查询指定知识库，不存在时由公共错误边界返回 404。 */
    @GetMapping("/{baseId}")
    public KnowledgeBaseResponse findById(@PathVariable String baseId, HttpServletRequest request) {
        LoginUserContext.require(request);
        return KnowledgeBaseResponse.from(bases.findById(baseId));
    }

    /** 修改知识库名称，保留服务端管理的状态与创建时间。 */
    @PutMapping("/{baseId}")
    public KnowledgeBaseResponse rename(@PathVariable String baseId,
            @Valid @RequestBody SaveKnowledgeBaseRequest body, HttpServletRequest request) {
        LoginUserContext.require(request);
        return KnowledgeBaseResponse.from(bases.rename(baseId, body.name()));
    }
}
