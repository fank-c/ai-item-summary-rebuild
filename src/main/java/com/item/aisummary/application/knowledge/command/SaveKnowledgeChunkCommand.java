package com.item.aisummary.application.knowledge.command;

import com.item.aisummary.domain.knowledge.value.Heading;
import java.util.List;

/** 调用方提供正文与元数据；没有 ID 和 revisionId，归属由保存入口统一确定。 */
public record SaveKnowledgeChunkCommand(
        /** 同一版本内从 0 开始且唯一的片段序号。 */
        int sequenceNo,
        /** 片段正文，保存时不由服务截断。 */
        String content,
        /** 从根标题到当前片段的有序标题路径。 */
        List<Heading> headingPath,
        /** 分词器统计值；未知时为空。 */
        Integer tokenCount,
        /** 处理后全文中的 UTF-16 起始偏移；未知时为空。 */
        Integer startOffset,
        /** 处理后全文中的 UTF-16 结束偏移；未知时为空。 */
        Integer endOffset,
        /** 来源起始页，从 1 开始；未知时为空。 */
        Integer pageStart,
        /** 来源结束页，包含该页；未知时为空。 */
        Integer pageEnd) {

    /** 避免调试日志输出完整正文，只保留批次定位所需的序号。 */
    @Override
    public String toString() {
        return "SaveKnowledgeChunkCommand[sequenceNo=" + sequenceNo + "]";
    }
}
