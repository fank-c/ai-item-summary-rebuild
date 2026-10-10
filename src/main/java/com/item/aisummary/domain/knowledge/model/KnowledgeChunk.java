package com.item.aisummary.domain.knowledge.model;

import com.item.aisummary.domain.knowledge.rule.KnowledgeRules;
import com.item.aisummary.domain.knowledge.value.Heading;
import java.time.Instant;
import java.util.List;

/** 调用方提供的片段数据；位置相对处理后全文，未知信息保持为空。 */
public record KnowledgeChunk(
        /** 片段业务 ID。 */
        String id,
        /** 所属文档版本 ID。 */
        String revisionId,
        /** 同一版本内从 0 开始的片段序号。 */
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
    public KnowledgeChunk {
        KnowledgeRules.requireChunk(
                sequenceNo, content, tokenCount, startOffset, endOffset, pageStart, pageEnd);
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
                + ", sequenceNo="
                + sequenceNo
                + "]";
    }
}
