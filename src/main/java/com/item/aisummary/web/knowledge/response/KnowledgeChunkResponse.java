package com.item.aisummary.web.knowledge.response;

import com.item.aisummary.domain.knowledge.model.KnowledgeChunk;
import com.item.aisummary.domain.knowledge.value.Heading;
import java.time.Instant;
import java.util.List;

/**
 * 已持久化知识切片片段的 HTTP 响应记录。
 *
 * <p>用于对外暴露单次构建产生的上下文块或检索块详情，包含片段正文、父子引用关系、
 * 层级标题路径以及在规整全文中的 UTF-16 偏移和页码坐标；不直接展开向量内容。
 *
 * @param id 片段业务 ID，32 位全局唯一业务标识；检索块在向量库中复用此标识
 * @param revisionId 所属文档版本 ID
 * @param parentChunkId 同版本无父 CONTEXT 上下文块的 ID；仅当本片段为 RETRIEVAL 且存在父块时有值，无父块或本片段为 CONTEXT 时为 null
 * @param chunkRole 片段在切分体系中的角色字符串，包含 CONTEXT（上下文块）与 RETRIEVAL（检索块）
 * @param sequenceNo 版本内非负且唯一的全局排序序号，从 0 开始递增，两种角色共同占用，不直接表示原文中的字符位置
 * @param content 处理后的片段正文文本内容
 * @param headingPath 从文档根标题到当前片段的有序标题路径列表；格式不支持或无法提取大纲时为 null，存在解析上下文但无标题时为空列表 []
 * @param tokenCount 本片段正文对应的 Token 统计数量，单位为 Token；基于分词器离线统计，无法可靠统计或未计算时为 null
 * @param startOffset 处理后规范化全文中的 UTF-16 起始字符偏移量（包含该位置，从 0 开始）；无法可靠映射到全文时为 null
 * @param endOffset 处理后规范化全文中的 UTF-16 结束字符偏移量（不包含该位置，满足 startOffset &lt; endOffset）；无法可靠映射到全文时为 null
 * @param pageStart 来源文档的起始页码，单位为页（从 1 开始）；无分页概念或无法确定页码时为 null
 * @param pageEnd 来源文档的结束页码，包含该页（满足 pageStart &lt;= pageEnd）；无分页概念或无法确定页码时为 null
 * @param createAt 片段记录创建时间，按 UTC 微秒精度记录
 */
public record KnowledgeChunkResponse(
        String id,
        String revisionId,
        String parentChunkId,
        String chunkRole,
        int sequenceNo,
        String content,
        List<HeadingResponse> headingPath,
        Integer tokenCount,
        Integer startOffset,
        Integer endOffset,
        Integer pageStart,
        Integer pageEnd,
        Instant createAt) {

    /**
     * 片段标题路径中单个层级标题项的 HTTP 响应记录。
     *
     * @param level 标题层级，单位为级（从 1 开始的正整数，如 1 表示一级标题）
     * @param title 标题文本内容；去除格式标记但保留 Markdown 等行内语法（inline syntax）
     */
    public record HeadingResponse(
            int level,
            String title) {

        /**
         * 将领域标题项值对象转换为 HTTP 响应记录。
         *
         * @param heading 领域标题项值对象
         * @return 映射后的标题项 HTTP 响应记录
         */
        public static HeadingResponse from(Heading heading) {
            return new HeadingResponse(heading.level(), heading.title());
        }
    }

    /**
     * 将知识切片片段领域实体转换为 HTTP 响应记录。
     *
     * <p>严格保留标题路径未知（null）与无标题空路径（空列表 []）的语义区别。
     *
     * @param chunk 知识片段领域实体
     * @return 映射后的知识片段 HTTP 响应记录
     */
    public static KnowledgeChunkResponse from(KnowledgeChunk chunk) {
        List<HeadingResponse> headingPath = chunk.headingPath() == null
                ? null
                : chunk.headingPath().stream().map(HeadingResponse::from).toList();

        return new KnowledgeChunkResponse(
                chunk.id(),
                chunk.revisionId(),
                chunk.parentChunkId(),
                chunk.chunkRole().value(),
                chunk.sequenceNo(),
                chunk.content(),
                headingPath,
                chunk.tokenCount(),
                chunk.startOffset(),
                chunk.endOffset(),
                chunk.pageStart(),
                chunk.pageEnd(),
                chunk.createAt());
    }
}
