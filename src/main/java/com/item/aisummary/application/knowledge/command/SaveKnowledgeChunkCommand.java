package com.item.aisummary.application.knowledge.command;

import com.item.aisummary.domain.knowledge.value.Heading;
import java.util.List;

/** 调用方提供正文与元数据；没有 ID 和 revisionId，归属由保存入口统一确定。 */
public record SaveKnowledgeChunkCommand(int sequenceNo, String content, List<Heading> headingPath,
                                        Integer tokenCount, Integer startOffset, Integer endOffset,
                                        Integer pageStart, Integer pageEnd) {
    @Override public String toString() { return "SaveKnowledgeChunkCommand[sequenceNo=" + sequenceNo + "]"; }
}
