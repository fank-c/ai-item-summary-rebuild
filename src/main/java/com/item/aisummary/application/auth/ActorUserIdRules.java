package com.item.aisummary.application.auth;

/** 文件所有者的身份约束，按 Unicode 码点计数，不裁剪或改写显式身份。 */
public final class ActorUserIdRules {
    public static final int MAX_CHARACTERS = 64;

    private ActorUserIdRules() { }

    public static void requireValid(String actorUserId) {
        if (actorUserId == null || actorUserId.isBlank()) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }
        if (actorUserId.codePointCount(0, actorUserId.length()) > MAX_CHARACTERS) {
            throw new IllegalArgumentException("用户 ID 长度不能超过 64 个字符");
        }
    }
}
