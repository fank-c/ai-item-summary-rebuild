package com.item.aisummary.domain.knowledge.model;

import com.item.aisummary.domain.knowledge.enums.ChunkRole;
import com.item.aisummary.domain.knowledge.rule.KnowledgeRules;
import com.item.aisummary.domain.knowledge.value.Heading;
import java.time.Instant;
import java.util.List;

/** 不可变的上下文或检索片段；父子使用同一处理后全文参照，未知位置保持为空。 */
public record KnowledgeChunk(
        /** 片段业务 ID。 */
        String id,
        /** 所属文档版本 ID。 */
        String revisionId,
        /** 同版本无父 CONTEXT 块的 ID；实际存在与归属由保存服务校验。 */
        String parentChunkId,
        /** 上下文或检索用途，不表示文档章节层级。 */
        ChunkRole chunkRole,
        /** 全版本范围内非负且唯一的全局序号，不表示原文位置。 */
        int sequenceNo,
        /** 处理后的片段正文。 */
        String content,
        /** 从文档根标题到当前片段的有序标题路径。 */
        List<Heading> headingPath,
        /** 使用的分词器统计值；未知时为空。 */
        Integer tokenCount,
        /** 处理后全文中的 UTF-16 起始偏移；未知时为空。 */
        Integer startOffset,
        /** 处理后全文中的 UTF-16 结束偏移；未知时为空。 */
        Integer endOffset,
        /** 来源起始页，从 1 开始；未知时为空。 */
        Integer pageStart,
        /** 来源结束页，包含该页；未知时为空。 */
        Integer pageEnd,
        /** 片段创建时间。 */
        Instant createAt) {
    /**
     * 保留旧的普通片段构造用法，并明确映射为无父级检索片段。
     */
    public KnowledgeChunk(
            String id,
            String revisionId,
            int sequenceNo,
            String content,
            List<Heading> headingPath,
            Integer tokenCount,
            Integer startOffset,
            Integer endOffset,
            Integer pageStart,
            Integer pageEnd,
            Instant createAt) {
        this(id, revisionId, null, ChunkRole.RETRIEVAL, sequenceNo, content, headingPath,
                tokenCount, startOffset, endOffset, pageStart, pageEnd, createAt);
    }

    public KnowledgeChunk {
        KnowledgeRules.requireChunk(
                sequenceNo, content, tokenCount, startOffset, endOffset, pageStart, pageEnd);
        if (chunkRole == null) {
            throw new IllegalArgumentException("知识片段角色不能为空");
        }
        if (parentChunkId != null) {
            if (parentChunkId.isBlank() || parentChunkId.length() > 32) {
                throw new IllegalArgumentException("父片段 ID 不能为空白且最多 32 个字符");
            }
            if (parentChunkId.equals(id)) {
                throw new IllegalArgumentException("片段不能引用自身为父片段");
            }
        }
        if (chunkRole == ChunkRole.CONTEXT && parentChunkId != null) {
            throw new IllegalArgumentException("上下文片段不能具有父片段");
        }
        if (headingPath != null) {
            if (headingPath.stream().anyMatch(heading -> heading == null)) {
                throw new IllegalArgumentException("标题路径不能包含空项");
            }
            headingPath = List.copyOf(headingPath);
        }
    }

    @Override
    public String toString() {
        return "KnowledgeChunk[id="
                + id
                + ", revisionId="
                + revisionId
                + ", parentChunkId="
                + parentChunkId
                + ", chunkRole="
                + chunkRole
                + ", sequenceNo="
                + sequenceNo
                + "]";
    }
}
