package com.item.aisummary.application.file.content;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** 原始文件内容资源；下游用 try-with-resources 关闭，不承担解析和向量化。 */
public record FileContentResource(String fileId, String originName, String mime, long size,
                                  String sha256, InputStream inputStream) implements Closeable {
    public FileContentResource { Objects.requireNonNull(inputStream, "内容流不能为空"); }
    @Override public void close() throws IOException { inputStream.close(); }
}
