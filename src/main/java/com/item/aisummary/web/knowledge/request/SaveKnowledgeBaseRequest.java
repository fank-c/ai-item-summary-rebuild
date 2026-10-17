package com.item.aisummary.web.knowledge.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 知识库创建和改名请求；业务服务在校验后裁剪首尾空白，允许同名知识库。
 *
 * @param name 必填名称，HTTP 输入最多 100 个 UTF-16 字符
 */
public record SaveKnowledgeBaseRequest(
        @NotBlank(message = "知识库名称不能为空")
        @Size(max = 100, message = "知识库名称不能超过 100 个字符") String name) {}
