package com.item.aisummary.application.file.content;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** 原始文件内容资源；下游用 try-with-resources 关闭，不承担解析和向量化。 */
public record FileContentResource(
        /** 文件元数据 ID；用于追踪来源，不用于替代流资源。 */
        String fileId,
        /** 文件原始展示名称。 */
        String originName,
        /** 规范化后的 MIME 类型。 */
        String mime,
        /** 数据库记录的实际文件大小，单位为字节。 */
        long size,
        /** 数据库记录的文件 SHA-256 摘要。 */
        String sha256,
        /** 对象存储原始内容流，资源所有权交给本对象调用方。 */
        InputStream inputStream)
        implements Closeable {

    public FileContentResource {
        Objects.requireNonNull(inputStream, "内容流不能为空");
    }

    /** 关闭底层对象存储响应流，释放网络资源。 */
    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
