package com.item.aisummary.domain.knowledge.enums;

/** 知识片段在两层切片中的领域角色及其稳定持久化值。 */
public enum ChunkRole {
    /** 较大范围的上下文块，不指向父片段。 */
    CONTEXT("CONTEXT"),
    /** 面向检索的块，可关联同版本上下文父片段。 */
    RETRIEVAL("RETRIEVAL");

    private final String value;

    ChunkRole(String value) {
        this.value = value;
    }

    /** 获取持久化存储的字符串值。 */
    public String value() {
        return value;
    }

    /** 将稳定持久化值还原为领域角色；未知值不予接受。 */
    public static ChunkRole fromValue(String value) {
        for (ChunkRole role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("未知的知识片段角色");
    }
}
