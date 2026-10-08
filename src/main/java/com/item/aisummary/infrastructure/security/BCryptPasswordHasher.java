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
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码的 UTF-8 长度不能超过 72 字节");
        }
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
