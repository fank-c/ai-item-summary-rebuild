package com.item.aisummary.application.knowledge.indexing.parse;

/**
 * 解析阶段产出的内容块类别枚举。
 *
 * <p>表示源文件中内容段落的排版或语义类型（由解析器提取），
 * 用于指导后续切分策略（例如保持代码块完整、避免拆分表格等）。
 *
 * <p>此类型不同于持久化层片段实体的 {@code ChunkRole}（CONTEXT/RETRIEVAL）。
 */
public enum ContentBlockType {
    /** 普通自然语言段落文本。 */
    TEXT,
    /** 各级标题文本。 */
    HEADING,
    /** 代码块或行内代码片段。 */
    CODE,
    /** 结构化表格内容。 */
    TABLE
}
