package com.item.aisummary.application.knowledge.command;

import com.item.aisummary.domain.knowledge.value.Heading;
import java.util.List;

/**
 * 批量保存知识片段命令模型（Command）。
 *
 * <p>由调用方（如 {@link com.item.aisummary.application.knowledge.service.KnowledgeIndexBuildService}）准备，
 * 作为参数传递给 {@link com.item.aisummary.application.knowledge.service.KnowledgeChunkService#saveBatch}。
 * 不包含片段 ID 与版本 ID，归属与主键由保存服务入口在事务内统一分配和绑定。
 *
 * @param sequenceNo 同一版本内从 0 开始且批内唯一的片段序号
 * @param content 片段文本正文（非空且非空白，保存时原样存储不截断）
 * @param headingPath 从根标题到当前片段的有序标题路径快照；未知时为 null
 * @param tokenCount 分词器计算的 Token 数（必须非负）；未知时为 null
 * @param startOffset 处理后全文中的 UTF-16 起始字符偏移；未知时为 null
 * @param endOffset 处理后全文中的 UTF-16 结束字符偏移；未知时为 null
 * @param pageStart 源文件中的起始页码（从 1 开始）；未知时为 null
 * @param pageEnd 源文件中的结束页码（包含该页）；未知时为 null
 */
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
