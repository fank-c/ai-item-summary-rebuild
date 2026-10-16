package com.item.aisummary.infrastructure.knowledge.indexing.parse.pdf;

import java.util.List;
import java.util.Set;

/**
 * 阿里云文档智能（Document Mind）PDF 云端解析包内隔离接口。
 *
 * <p>设计目的与边界：
 * <ul>
 *   <li>隔离云端网络与 SDK 细节：作为 {@link AlibabaPdfKnowledgeDocumentParser} 与实际云端客户端（{@link DocMindPdfParserClient}）之间的边界。</li>
 *   <li>支持无网络确定性单测：在测试环境中无需真实凭据或网络访问，即可通过 Mock 或 Stub 替身进行版面映射与覆盖率验证。</li>
 * </ul>
 */
@FunctionalInterface
public interface DocMindPdfParser {

    /**
     * 调用云端文档智能能力，解析 PDF 原文字节并返回版面分析与已处理页面清单。
     *
     * @param pdfBytes 完整的 PDF 文档二进制字节（受控于内存预算）
     * @return 包含版面列表与已处理页码集合的解析结果记录
     * @throws IllegalArgumentException 当源字节为空、云端任务失败或超时时抛出
     */
    Result parse(byte[] pdfBytes);

    /**
     * 云端解析执行结果包装记录。
     *
     * @param layouts 从云端分页拉取并汇聚的全部版面元素列表
     * @param processedPageIds 云端返回的已完成识别与版面分析的页码集合（用于核对文档是否完整覆盖，防漏页）
     */
    record Result(List<Layout> layouts, Set<Integer> processedPageIds) {
        public Result {
            layouts = layouts == null ? null : List.copyOf(layouts);
            processedPageIds = processedPageIds == null ? Set.of() : Set.copyOf(processedPageIds);
            if (processedPageIds.stream().anyMatch(page -> page == null || page < 0)) {
                throw new IllegalArgumentException("已处理页码无效");
            }
        }
    }

    /**
     * 阿里云文档智能返回的单个版面结构元素。
     *
     * @param text 纯文本内容
     * @param markdownContent Markdown 格式的内容（如表格或段落）
     * @param llmResult 视觉大模型（VLM）增强结果（如 HTML 表格、图片描述说明）
     * @param type 版面一级类型（如 text、title、table、figure）
     * @param subType 版面二级子类型（如 doc_title、para_title、picture）
     * @param level 版面嵌套层级（注意：仅表示版面排版嵌套，不能直接作为文档大纲章节层级）
     * @param pageNum 官方 0 基页码（取值 0 ~ pageCount-1）
     * @param index 全局阅读顺序索引（非负、单调递增，决定多栏等复杂排版的正文顺序）
     */
    record Layout(
            String text,
            String markdownContent,
            String llmResult,
            String type,
            String subType,
            Integer level,
            Integer pageNum,
            Integer index) {}
}
