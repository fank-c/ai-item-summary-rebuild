package com.item.aisummary.application.file;

import java.time.Instant;

public record FileDownloadUrl(String fileId, String url, Instant expiresAt) { }
