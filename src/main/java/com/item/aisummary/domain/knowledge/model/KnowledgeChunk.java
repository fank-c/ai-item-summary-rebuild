package com.item.aisummary.domain.knowledge.model;

import com.item.aisummary.domain.knowledge.rule.KnowledgeRules;
import com.item.aisummary.domain.knowledge.value.Heading;
import java.time.Instant;
import java.util.List;

/** 调用方提供的片段数据；位置相对处理后全文，未知信息保持为空。 */
public record KnowledgeChunk(String id, String revisionId, int sequenceNo, String content,
                             List<Heading> headingPath, Integer tokenCount, Integer startOffset,
                             Integer endOffset, Integer pageStart, Integer pageEnd, Instant createAt) {
    public KnowledgeChunk {
        KnowledgeRules.requireChunk(sequenceNo, content, tokenCount, startOffset, endOffset, pageStart, pageEnd);
        if (headingPath != null) {
            if (headingPath.stream().anyMatch(heading -> heading == null)) {
                throw new IllegalArgumentException("标题路径不能包含空项");
            }
            headingPath = List.copyOf(headingPath);
        }
    }

    @Override public String toString() {
        return "KnowledgeChunk[id=" + id + ", revisionId=" + revisionId + ", sequenceNo=" + sequenceNo + "]";
    }
}
