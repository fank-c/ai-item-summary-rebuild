package com.item.aisummary.domain.knowledge.enums;

public enum DocumentIndexStatus {
    PENDING("pending"), INDEXED("indexed"), FAILED("failed"), REMOVED("removed");
    private final String value;
    DocumentIndexStatus(String value) { this.value = value; }
    public String value() { return value; }
    public static DocumentIndexStatus fromValue(String value) {
        for (var status : values()) if (status.value.equals(value)) return status;
        throw new IllegalArgumentException("未知的文档索引状态");
    }
}
