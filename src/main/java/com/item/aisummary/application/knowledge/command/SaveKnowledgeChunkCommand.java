package com.item.aisummary.application.knowledge.command;

import com.item.aisummary.domain.knowledge.enums.ChunkRole;
import com.item.aisummary.domain.knowledge.value.Heading;
import java.util.List;

/**
 * 批量保存知识片段命令模型（Command）。
 *
 * <p>不包含片段 ID 与版本 ID，归属与主键由保存服务入口统一分配和绑定。
 * 父引用使用同一版本的全局序号，可指向本批或已保存的 CONTEXT 块；服务解析为业务 ID。
 * CONTEXT 块无父，RETRIEVAL 块可独立存在或引用 CONTEXT 块，不接受多层树。
 *
 * @param parentSequenceNo 同一版本内父块的全局序号；无父块时为 null
 * @param chunkRole 片段用途，必须显式提供；兼容构造器使用 RETRIEVAL
 * @param sequenceNo 同一版本内非负且批内唯一的全局序号，不表示原文位置
 * @param content 片段文本正文（非空且非空白，保存时原样存储不截断）
 * @param headingPath 从根标题到当前片段的有序标题路径快照；未知时为 null
 * @param tokenCount 分词器计算的 Token 数（必须非负）；未知时为 null
 * @param startOffset 处理后全文中的 UTF-16 起始字符偏移；未知时为 null
 * @param endOffset 处理后全文中的 UTF-16 结束字符偏移；未知时为 null
 * @param pageStart 源文件中的起始页码（从 1 开始）；未知时为 null
 * @param pageEnd 源文件中的结束页码（包含该页）；未知时为 null
 */
public record SaveKnowledgeChunkCommand(
        /** 同版本父 CONTEXT 块的全局序号，不传递尚未分配的业务 ID。 */
        Integer parentSequenceNo,
        /** 上下文或检索用途，不表示文件格式或内容类别。 */
        ChunkRole chunkRole,
        /** 同一版本内非负且唯一的全局序号。 */
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

    /** 原有平铺片段入口，明确创建无父的 RETRIEVAL 块。 */
    public SaveKnowledgeChunkCommand(
            int sequenceNo, String content, List<Heading> headingPath, Integer tokenCount,
            Integer startOffset, Integer endOffset, Integer pageStart, Integer pageEnd) {
        this(null, ChunkRole.RETRIEVAL, sequenceNo, content, headingPath, tokenCount,
                startOffset, endOffset, pageStart, pageEnd);
    }

    /** 避免调试日志输出完整正文，只保留批次定位所需的序号。 */
    @Override
    public String toString() {
        return "SaveKnowledgeChunkCommand[sequenceNo=" + sequenceNo + "]";
    }
}
