package com.item.aisummary.application.knowledge.indexing.source;

import com.item.aisummary.application.file.content.FileContentResource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 源文件原始字节准备与摘要校验组件。
 *
 * <p>负责在将文件传递给具体格式解析器之前，从对象存储内容流中完整读取原始字节，
 * 并执行安全大小限制与 SHA-256 完整性核对。
 *
 * <p>核心流程与流资源边界：
 * <ul>
 *   <li>原始输入流由外层调用者使用 try-with-resources 负责关闭；本组件在读取过程中完整消费至 EOF。</li>
 *   <li>读取过程中累计实际字节大小；若超过 {@code maxSourceSize} 预算立即中断，防止恶意超大文件消耗内存。</li>
 *   <li>消费完毕后比对实际读入的字节大小与文件元数据记录的大小，并比对计算所得 SHA-256 摘要与文件元数据摘要。</li>
 *   <li>校验通过后将受控字节数组包装为 {@link PreparedSource}，供后续解析器重复读取。</li>
 *   <li>不进行公共字符集解码；文本 UTF-8 严格验证由对应的文本解析器负责。</li>
 * </ul>
 */
public final class KnowledgeSourceReader {
    private final long maxSourceSize;

    /**
     * 构造源文件读取器。
     *
     * @param maxSourceSize 单个源文件的最大允许读取字节数（必须为正且小于 Integer.MAX_VALUE）
     * @throws IllegalArgumentException 当大小上限非法时抛出
     */
    public KnowledgeSourceReader(long maxSourceSize) {
        if (maxSourceSize < 1 || maxSourceSize >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("源文件大小上限必须为正且小于 Integer.MAX_VALUE 字节");
        }
        this.maxSourceSize = maxSourceSize;
    }

    /**
     * 限量读取源文件字节流并验证大小与 SHA-256 摘要一致性。
     *
     * @param resource 打开的源文件资源对象（包含元数据与输入流）
     * @param format 解析前确认的文件格式
     * @return 包含已核验受控字节的 PreparedSource
     * @throws IllegalArgumentException 当资源或格式为空时抛出
     * @throws KnowledgeSourceException 当读取失败、文件超限、大小不匹配或摘要不一致时抛出
     */
    public PreparedSource read(FileContentResource resource, FileFormat format) {
        if (resource == null || format == null) {
            throw new IllegalArgumentException("源资源和格式不能为空");
        }
        if (resource.size() < 0 || resource.size() > maxSourceSize) {
            throw failure("SOURCE_SIZE_EXCEEDED", "源文件大小超过限制");
        }
        if (resource.sha256() == null || !resource.sha256().matches("[0-9a-fA-F]{64}")) {
            throw failure("SOURCE_DIGEST_MISSING", "源文件缺少有效摘要");
        }
        MessageDigest digest = sha256();
        var buffer = new byte[8192];
        var output = new ByteArrayOutputStream();
        long total = 0;
        try {
            int read;
            while ((read = resource.inputStream().read(buffer)) != -1) {
                if (read == 0) {
                    int one = resource.inputStream().read();
                    if (one == -1) break;
                    buffer[0] = (byte) one;
                    read = 1;
                }
                total += read;
                if (total > maxSourceSize) throw failure("SOURCE_SIZE_EXCEEDED", "源文件大小超过限制");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (IOException exception) {
            throw new KnowledgeSourceException("SOURCE_READ_FAILED", "源文件读取失败", exception);
        }
        if (total != resource.size()) throw failure("SOURCE_SIZE_MISMATCH", "源文件实际大小与记录不一致");
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(resource.sha256())) {
            throw failure("SOURCE_DIGEST_MISMATCH", "源文件摘要与记录不一致");
        }
        return new PreparedSource(resource.fileId(), resource.originName(), resource.mime(), format, actual, output.toByteArray());
    }

    private static KnowledgeSourceException failure(String code, String message) {
        return new KnowledgeSourceException(code, message);
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 算法不可用", exception); }
    }
}
