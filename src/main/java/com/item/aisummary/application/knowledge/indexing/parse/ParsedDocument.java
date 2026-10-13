package com.item.aisummary.application.knowledge.indexing.parse;

import java.util.List;

/**
 * 统一的解析文档数据合同。
 *
 * <p>封装格式解析策略输出的所有有序内容块。
 * 集合以不可修改的防御性列表存储，且内容块的 {@code ordinal} 必须从 0 开始连续递增，保证全局阅读顺序保真。
 *
 * <p>本对象是应用层内存数据传输载体，不映射为数据库实体，不依赖 Spring AI Document 类型。
 *
 * @param blocks 文档按阅读顺序包含的有序内容块列表（非空）
 */
public record ParsedDocument(List<ContentBlock> blocks) {
    public ParsedDocument {
        if (blocks == null || blocks.isEmpty() || blocks.stream().anyMatch(block -> block == null)) {
            throw new IllegalArgumentException("解析文档必须包含有效内容块");
        }
        blocks = List.copyOf(blocks);
        for (int index = 0; index < blocks.size(); index++) {
            if (blocks.get(index).ordinal() != index) {
                throw new IllegalArgumentException("解析内容块必须按从 0 连续的阅读顺序返回");
            }
        }
    }

    /**
     * 避免输出完整正文，只记录内容块总数。
     */
    @Override public String toString() {
        return "ParsedDocument[blockCount=" + blocks.size() + "]";
    }
}
