package com.item.aisummary.infrastructure.security;

import com.item.aisummary.application.user.port.PasswordHasher;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** 生成无算法前缀的 BCrypt 摘要，兼容旧记录，且不静默截断长密码。 */
@Component
public final class BCryptPasswordHasher implements PasswordHasher {
    private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder();

    @Override
    public String encode(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        // BCrypt 的输入预算按 UTF-8 字节计算；不能依靠 HTTP 的字符数上限代替。
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码的 UTF-8 长度不能超过 72 字节");
        }
        // 不 trim，不预哈希；编码器生成独立随机盐，保留密码中有效的首尾空格。
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        if (rawPassword == null || passwordHash == null) {
            return false;
        }
        return delegate.matches(rawPassword, passwordHash);
    }
}
