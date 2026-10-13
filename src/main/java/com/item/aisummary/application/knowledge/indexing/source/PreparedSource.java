package com.item.aisummary.application.knowledge.indexing.source;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * 完整读取且核对摘要后的源文件内容包装对象。
 *
 * <p>表示经过 {@link KnowledgeSourceReader} 校验完文件大小上限、实际读入字节数与 SHA-256 摘要
 * 后的受控内存数据，供各文件格式的解析策略消费。
 *
 * <p>设计要点与流资源说明：
 * <ul>
 *   <li>原始远端对象存储流在读取器内部已被完整消费到 EOF，并在外部由调用者通过 try-with-resources 关闭，不持有网络连接。</li>
 *   <li>内部接管读取器生成的字节数组，避免在内存中产生多重防御性拷贝。</li>
 *   <li>解析器可以通过 {@link #openStream()} 多次重复打开内存字节流，无需关心外部网络流资源管理。</li>
 *   <li>不对二进制文件（PDF、DOCX）做公共 UTF-8 校验，文本格式的字符编码校验完全封装在具体解析器内部。</li>
 * </ul>
 */
public final class PreparedSource {
    private final String fileId;
    private final String originName;
    private final String mime;
    private final FileFormat format;
    private final String sha256;
    private final byte[] bytes;

    /** 接管读取器新建且不再共享的数组，避免受控内存源重复复制；不得传入外部可变数组。 */
    PreparedSource(String fileId, String originName, String mime, FileFormat format,
            String sha256, byte[] bytes) {
        this.fileId = fileId;
        this.originName = originName;
        this.mime = mime;
        this.format = format;
        this.sha256 = sha256;
        this.bytes = bytes;
    }

    /** 获取源文件业务 ID。 */
    public String fileId() { return fileId; }

    /** 获取源文件原始展示名称。 */
    public String originName() { return originName; }

    /** 获取源文件 MIME 类型。 */
    public String mime() { return mime; }

    /** 获取解析路由的文件格式。 */
    public FileFormat format() { return format; }

    /** 获取源文件的实际 SHA-256 摘要（小写十六进制）。 */
    public String sha256() { return sha256; }

    /** 获取受控字节数组的实际大小（字节数）。 */
    public long size() { return bytes.length; }

    /**
     * 为解析器打开一个全新的内存输入流。
     *
     * @return 基于内部字节数组的 ByteArrayInputStream
     */
    public InputStream openStream() { return new ByteArrayInputStream(bytes); }

    /**
     * 避免输出完整原始字节或敏感文件名，仅打印元数据摘要。
     */
    @Override public String toString() {
        return "PreparedSource[fileId=" + fileId + ", format=" + format + ", size=" + size() + "]";
    }
}
