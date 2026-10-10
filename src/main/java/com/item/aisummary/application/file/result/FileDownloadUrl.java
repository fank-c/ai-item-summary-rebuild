package com.item.aisummary.application.file.result;

import java.time.Instant;

/** 下载地址是临时访问凭证；expiresAt 不证明远端对象存在。 */
public record FileDownloadUrl(
        /** 文件 ID，用于响应资源定位。 */
        String fileId,
        /** 对象存储签发的临时 GET 地址。 */
        String url,
        /** 临时地址的应用层过期时间。 */
        Instant expiresAt) {}
