package com.item.aisummary.infrastructure.knowledge.indexing.parse;

import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.application.knowledge.indexing.source.PreparedSource;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 文本格式解析共用的受控 UTF-8 解码工具。
 *
 * <p>为 TXT 与 Markdown 等基于文本的文档格式解析器提供严格的 UTF-8 字符集校验与换行符规整：
 * <ul>
 *   <li>使用配置为 REPORT 模式的 {@link CharsetDecoder}，拒绝畸形或截断的 UTF-8 字节；不猜测或转换其他编码。</li>
 *   <li>源文件内存流在 {@code try-with-resources} 块中管理，确保流资源在成功与失败路径均被正确关闭。</li>
 *   <li>解码失败顶层固定抛出安全、脱敏的异常文案，严禁在异常消息中回显源文档正文或展示文件名。</li>
 *   <li>严格校验源文档对象非空、格式匹配与大小预算上限。</li>
 *   <li>移除开头至多一个 UTF-8 BOM（{@code \uFEFF}），将正文所有换行统一为 LF（{@code \n}），正文其余字符原样保留。</li>
 * </ul>
 */
final class Utf8TextDecoder {
    /**
     * 单个文本解析文档允许输出的最大内容块数量安全预算。
     * 为 TXT 与 Markdown 解析器共用；常量非可调策略参数。
     */
    static final int MAX_BLOCKS = 100_000;

    private Utf8TextDecoder() {}

    /**
     * 校验并解码源文件受控字节为规范化文本。
     *
     * @param source 已核验大小与摘要的源文件包装对象
     * @param expectedFormat 期望的文件格式
     * @param maxSourceSize 单个源文件允许的最大字节预算
     * @return 规整后的 UTF-8 文本内容
     * @throws IllegalArgumentException 当源参数非法、格式不匹配、大小超限或 UTF-8 解码失败时抛出
     */
    static String decode(PreparedSource source, FileFormat expectedFormat, long maxSourceSize) {
        if (maxSourceSize < 1 || maxSourceSize >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("源文件大小上限必须为正且小于 Integer.MAX_VALUE 字节");
        }
        if (source == null) {
            throw new IllegalArgumentException("源文件对象不能为空");
        }
        if (expectedFormat == null) {
            throw new IllegalArgumentException("期望文件格式不能为空");
        }
        if (source.format() != expectedFormat) {
            throw new IllegalArgumentException("源文件格式不匹配");
        }
        if (source.size() < 0 || source.size() > maxSourceSize) {
            throw new IllegalArgumentException("源文件大小超过预算限制");
        }

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        String text;
        // 直接增量解码已准备的内存流，不再次复制整份原字节；Reader 关闭其拥有的底层流。
        InputStream input = source.openStream();
        if (input == null) throw new IllegalArgumentException("源文件输入流不能为空");
        try (var reader = new InputStreamReader(bounded(input, maxSourceSize), decoder)) {
            var output = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) output.append(buffer, 0, read);
            text = output.toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("源文件 UTF-8 解码失败", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("源文件读取失败", exception);
        }
        // 部分二进制/UTF-16 字节恰巧也是合法 UTF-8；NUL 不属于本版支持的文本内容。
        // 这不是编码探测，ASCII 等不同编码共享的字节也无法据此判断其原始编码。
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("文本内容不能包含 NUL 字符");
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }

        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static InputStream bounded(InputStream input, long maximum) {
        return new FilterInputStream(input) {
            private long total;
            @Override public int read() throws IOException {
                int result = in.read();
                count(result < 0 ? 0 : 1);
                return result;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int result = in.read(bytes, offset, length);
                count(Math.max(0, result));
                return result;
            }
            private void count(int read) {
                total += read;
                if (total > maximum) throw new IllegalArgumentException("源文件大小超过预算限制");
            }
        };
    }
}
