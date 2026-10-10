package com.item.aisummary.domain.knowledge.enums;

/** 只描述持久化状态，不提供构建或发布状态机。 */
public enum RevisionStatus {
    BUILDING("building"), READY("ready"), FAILED("failed"), SUPERSEDED("superseded");
    private final String value;
    RevisionStatus(String value) { this.value = value; }
    public String value() { return value; }
    public static RevisionStatus fromValue(String value) {
        for (var status : values()) if (status.value.equals(value)) return status;
        throw new IllegalArgumentException("未知的版本状态");
    }
}
