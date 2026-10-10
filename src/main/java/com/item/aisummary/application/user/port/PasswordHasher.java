package com.item.aisummary.application.user.port;

/** 密码摘要能力；不负责登录、权限或凭据存储。 */
public interface PasswordHasher {
    /** 原始密码不得裁剪；编码失败由实现以稳定的校验异常报告。 */
    String encode(String rawPassword);
    /** 与已有摘要比较，不重新编码或写入；该能力本身不形成登录流程。 */
    boolean matches(String rawPassword, String passwordHash);
}
