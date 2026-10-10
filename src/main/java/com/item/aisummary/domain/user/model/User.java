package com.item.aisummary.domain.user.model;

import java.time.Instant;

/** 用户业务数据；持久化映射和 HTTP 序列化由各自的适配层负责。 */
public record User(String id, String name, String email, String passwordHash, int status,
                   Instant deletedAt, Instant createAt, Instant updateAt) {
    /** 普通文本与路径 ID 的最小规范化规则；密码和显式身份 ID 不使用此裁剪。 */
    public static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    // 避免调试或隐式字符串转换暴露密码摘要和个人资料。
    @Override
    public String toString() {
        return "User[id=" + id + "]";
    }
}
