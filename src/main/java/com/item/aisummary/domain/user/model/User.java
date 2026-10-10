package com.item.aisummary.domain.user.model;

import java.time.Instant;

/** 用户业务数据；持久化映射和 HTTP 序列化由各自的适配层负责。 */
public record User(
        /** 用户业务 ID。 */
        String id,
        /** 用户展示名称。 */
        String name,
        /** 用户邮箱。 */
        String email,
        /** BCrypt 等摘要算法生成的密码摘要。 */
        String passwordHash,
        /** 账户业务状态；与逻辑删除状态独立。 */
        int status,
        /** 逻辑删除时间；为 null 表示未删除。 */
        Instant deletedAt,
        /** 用户创建时间。 */
        Instant createAt,
        /** 用户最后更新时间。 */
        Instant updateAt) {
    /**
     * 校验普通文本字段非空，并返回裁剪首尾空白后的值。
     *
     * <p>适用于用户名、邮箱和路径 ID；明文密码与显式身份 ID 不使用此规则裁剪。
     *
     * @param value 待校验的原始字符串
     * @param field 字段名称，用于异常提示信息
     * @return 裁剪首尾空白后的有效字符串
     * @throws IllegalArgumentException 字符串为 null 或空白
     */
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
