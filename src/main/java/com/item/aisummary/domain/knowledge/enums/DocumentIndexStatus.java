package com.item.aisummary.domain.knowledge.enums;

/** 文档索引生命周期状态，当前只保存状态值，不提供状态机转换。 */
public enum DocumentIndexStatus {
    /** 等待建立索引或等待后续处理。 */
    PENDING("pending"),
    /** 已完成索引并可用于查询。 */
    INDEXED("indexed"),
    /** 最近一次索引处理失败。 */
    FAILED("failed"),
    /** 文档已移除或不再参与索引。 */
    REMOVED("removed");

    private final String value;

    DocumentIndexStatus(String value) {
        this.value = value;
    }

    /** 获取持久化存储的字符串值。 */
    public String value() {
        return value;
    }

    /**
     * 将数据库中的稳定字符串还原为领域枚举。
     *
     * @param value 数据库存储的枚举字符串
     * @return 对应的文档索引状态枚举
     * @throws IllegalArgumentException 未知枚举值
     */
    public static DocumentIndexStatus fromValue(String value) {
        for (DocumentIndexStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的文档索引状态");
    }
}
