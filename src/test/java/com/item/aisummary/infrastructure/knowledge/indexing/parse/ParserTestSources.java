package com.item.aisummary.infrastructure.knowledge.indexing.parse;

import com.item.aisummary.application.file.content.FileContentResource;
import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.application.knowledge.indexing.source.KnowledgeSourceReader;
import com.item.aisummary.application.knowledge.indexing.source.PreparedSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 解析器测试辅助工具类。
 *
 * <p>通过真实 {@link KnowledgeSourceReader} 与 {@link FileContentResource} 构造受控的 {@link PreparedSource}，
 * 使用内存流与真实计算的 SHA-256 摘要，不发生外部网络或文件存储调用。
 *
 * <p>读取器预算上限统一采用 {@code Math.max(1, len)}，确保空源字节场景不会因预算非法导致构造失败。
 */
public final class ParserTestSources {
    private ParserTestSources() {}

    /**
     * 根据 UTF-8 字符串内容与目标文件格式构造测试源。
     *
     * @param content 文本内容；为 null 时视为空内容
     * @param format 目标文件格式
     * @return 准备完毕的受控源文件包装对象
     */
    public static PreparedSource source(String content, FileFormat format) {
        byte[] bytes = (content != null) ? content.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return source(bytes, format);
    }

    /**
     * 根据原始字节数组与目标文件格式构造测试源。
     *
     * @param bytes 原始字节数组；为 null 时视为空数组
     * @param format 目标文件格式
     * @return 准备完毕的受控源文件包装对象
     */
    public static PreparedSource source(byte[] bytes, FileFormat format) {
        byte[] data = (bytes != null) ? bytes : new byte[0];
        String digest = sha256(data);
        long size = data.length;
        String extension = (format != null) ? format.name().toLowerCase() : "txt";
        String originName = "test-doc." + extension;
        String mime = switch (format) {
            case TXT -> "text/plain";
            case MD -> "text/markdown";
            case PDF -> "application/pdf";
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case null -> "application/octet-stream";
        };

        long maxSourceSize = Math.max(1L, size);
        KnowledgeSourceReader reader = new KnowledgeSourceReader(maxSourceSize);
        try (FileContentResource resource = new FileContentResource(
                "test-file-id", originName, mime, size, digest, new ByteArrayInputStream(data))) {
            return reader.read(resource, format);
        } catch (IOException exception) {
            throw new IllegalStateException("测试源构造失败", exception);
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 算法不可用", exception);
        }
    }
}
