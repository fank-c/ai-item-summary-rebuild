package com.item.aisummary.application.auth;

/** 文件所有者的身份约束，按 Unicode 码点计数，不裁剪或改写显式身份。 */
public final class ActorUserIdRules {
    /** 模拟身份和内部调用身份允许的最大 Unicode 码点数。 */
    public static final int MAX_CHARACTERS = 64;

    private ActorUserIdRules() {}

    /**
     * 校验调用方身份 ID 的非空约束与 Unicode 码点长度限制。
     *
     * <p>身份 ID 不能为空白，且按 Unicode 码点计算长度不能超过 64（补充平面字符计 1 个码点）。
     * 不进行 trim 或大小写转换。
     *
     * @param actorUserId 调用方用户 ID
     * @throws IllegalArgumentException 用户 ID 为 null、空白或 Unicode 码点数超过 64
     */
    public static void requireValid(String actorUserId) {
        if (actorUserId == null || actorUserId.isBlank()) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }
        // 补充平面字符占两个 UTF-16 单元，但身份长度规则只计一个码点。
        if (actorUserId.codePointCount(0, actorUserId.length()) > MAX_CHARACTERS) {
            throw new IllegalArgumentException("用户 ID 长度不能超过 64 个字符");
        }
    }
}
