package com.item.aisummary.application.user.reference;

/** 跨模块仅传递资源归属所需的最小用户引用。 */
public record UserReference(
        /** 被引用用户 ID。 */
        String id,
        /** 被引用用户展示名称。 */
        String name) {}
