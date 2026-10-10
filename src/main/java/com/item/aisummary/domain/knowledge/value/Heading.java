package com.item.aisummary.domain.knowledge.value;

/** 有序标题路径中的一项；层级从 1 开始，不假定只来自 Markdown。 */
public record Heading(
        /** 标题层级，从 1 开始。 */
        int level,
        /** 标题文本，保留调用方提供的内容。 */
        String title) {
    /**
     * 创建标题项值对象并校验约束。
     *
     * @throws IllegalArgumentException 层级小于 1 或标题为空白
     */
    public Heading {
        if (level < 1) {
            throw new IllegalArgumentException("标题层级必须为正数");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
    }
}
