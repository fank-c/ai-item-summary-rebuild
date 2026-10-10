package com.item.aisummary.domain.knowledge.enums;

/** 文档版本的持久化状态，不提供构建或发布状态机。 */
public enum RevisionStatus {
    /** 正在构建片段或其他派生数据。 */
    BUILDING("building"),
    /** 构建完成但不一定已发布为当前版本。 */
    READY("ready"),
    /** 本次构建失败。 */
    FAILED("failed"),
    /** 已被更新版本替代。 */
    SUPERSEDED("superseded");

    private final String value;

    RevisionStatus(String value) {
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
     * @return 对应的版本状态枚举
     * @throws IllegalArgumentException 未知枚举值
     */
    public static RevisionStatus fromValue(String value) {
        for (RevisionStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的版本状态");
    }
}
