package com.item.aisummary.application.auth;

/** 当前请求携带的最小身份信息，不表示已完成真实认证。 */
public record CurrentUser(String id, String username) {
}
