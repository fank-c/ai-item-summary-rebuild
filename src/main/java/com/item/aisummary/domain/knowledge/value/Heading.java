package com.item.aisummary.domain.knowledge.value;

/** 有序标题路径中的一项；层级从 1 开始，不假定只来自 Markdown。 */
public record Heading(int level, String title) {
    public Heading {
        if (level < 1) throw new IllegalArgumentException("标题层级必须为正数");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("标题不能为空");
    }
}
