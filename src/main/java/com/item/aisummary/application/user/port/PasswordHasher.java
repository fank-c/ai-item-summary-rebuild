package com.item.aisummary.application.user.port;

/** 密码摘要能力；不负责登录、权限或凭据存储。 */
public interface PasswordHasher {
    String encode(String rawPassword);
    boolean matches(String rawPassword, String passwordHash);
}
