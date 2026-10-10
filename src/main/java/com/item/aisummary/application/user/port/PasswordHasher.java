package com.item.aisummary.application.user.port;

/** 密码摘要能力；不负责登录、权限或凭据存储。 */
public interface PasswordHasher {
    /**
     * 将明文密码计算为单向哈希摘要。
     *
     * <p>明文密码不得裁剪首尾空白；若超过字节限制或非法由实现抛出校验异常。
     *
     * @param rawPassword 明文密码
     * @return 计算后的密码摘要字符串
     * @throws IllegalArgumentException 密码为空白或超过算法支持的长度上限
     */
    String encode(String rawPassword);

    /**
     * 校验明文密码是否与已存储的密码摘要匹配。
     *
     * @param rawPassword 明文密码
     * @param passwordHash 已存储的密码摘要
     * @return 匹配返回 true，任一参数为 null 或不匹配返回 false
     */
    boolean matches(String rawPassword, String passwordHash);
}
