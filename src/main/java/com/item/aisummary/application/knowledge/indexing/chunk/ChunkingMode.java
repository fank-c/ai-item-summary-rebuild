package com.item.aisummary.application.knowledge.indexing.chunk;

/**
 * 知识内容分块模式枚举。
 *
 * <p>分块模式由构建入口显式指定，独立于源文件的物理格式：
 * <ul>
 *   <li>{@link #FLAT}：平铺切分，仅生成无父片段的检索块。</li>
 *   <li>{@link #PARENT_CHILD}：两层父子切分，生成大上下文父块及多个用于检索的子片段。</li>
 * </ul>
 */
public enum ChunkingMode {
    /** 平铺分块模式：所有切片均为独立的检索块（RETRIEVAL）。 */
    FLAT,
    /** 父子分块模式：切分出上下文父块（CONTEXT）及其关联检索子块（RETRIEVAL）。 */
    PARENT_CHILD
}
