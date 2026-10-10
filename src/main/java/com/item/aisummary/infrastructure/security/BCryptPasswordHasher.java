package com.item.aisummary.infrastructure.security;

import com.item.aisummary.application.user.port.PasswordHasher;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** 生成无算法前缀的 BCrypt 摘要，兼容旧记录，且不静默截断长密码。 */
@Component
public final class BCryptPasswordHasher implements PasswordHasher {
    private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder();

    /**
     * 计算原始密码的 BCrypt 摘要。
     *
     * <p>密码保留首尾空格，按 UTF-8 字节计算长度不可超过 72 字节。
     *
     * @param rawPassword 原始明文密码
     * @return BCrypt 单向哈希摘要
     * @throws IllegalArgumentException 密码为空白或 UTF-8 编码后超过 72 字节
     */
    @Override
    public String encode(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        // BCrypt 的输入预算按 UTF-8 字节计算，不能用 HTTP 字符数上限替代。
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码的 UTF-8 长度不能超过 72 字节");
        }
        // 不 trim、不预哈希；编码器生成独立随机盐，保留密码中的有效首尾空格。
        return delegate.encode(rawPassword);
    }

    /**
     * 校验明文密码是否与已有的哈希摘要匹配。
     *
     * @param rawPassword 原始明文密码
     * @param passwordHash 已存储的哈希摘要
     * @return 匹配成功返回 true，否则返回 false
     */
    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        if (rawPassword == null || passwordHash == null) {
            return false;
        }
        return delegate.matches(rawPassword, passwordHash);
    }
}
