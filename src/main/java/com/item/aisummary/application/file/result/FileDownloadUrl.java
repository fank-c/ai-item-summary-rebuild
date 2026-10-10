package com.item.aisummary.application.file.result;

import java.time.Instant;

/** 下载地址是临时访问凭证；expiresAt 为签发时计算的到期时间，不证明远端对象存在。 */
public record FileDownloadUrl(String fileId, String url, Instant expiresAt) { }
