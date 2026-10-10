package com.item.aisummary.application.auth;

/** 当前请求携带的最小身份信息，不表示已完成真实认证。 */
public record CurrentUser(
        /** 请求身份 ID；仅表示当前请求使用的身份，不代表已完成真实认证。 */
        String id,
        /** 身份展示名称；当前用例不把它当作权限依据。 */
        String username) {}
