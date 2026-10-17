package com.item.aisummary.web.knowledge.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 将已上传文件登记到知识库；调用者身份由服务端请求上下文取得。
 *
 * @param fileId 已上传源文件 ID，必填且最多 32 个字符；登记只查询元数据，不触发构建
 */
public record RegisterKnowledgeDocumentRequest(
        @NotBlank(message = "文件 ID 不能为空")
        @Size(max = 32, message = "文件 ID 不能超过 32 个字符") String fileId) {}
