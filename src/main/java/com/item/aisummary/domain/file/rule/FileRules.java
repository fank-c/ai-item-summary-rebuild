package com.item.aisummary.domain.file.rule;

import com.item.aisummary.domain.file.exception.FileValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 文件输入文本规则；只约束名称和 MIME，不识别实际内容格式。 */
public final class FileRules {
    private static final int MAX_OBJECT_KEY_UTF8_BYTES = 1023;
    private static final DateTimeFormatter UTC_DATE =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    private FileRules() {}

    /**
     * 校验上传文件名并检查其扩展名是否在允许的集合中。
     *
     * <p>保留裁剪首尾空格后的原始展示名称；仅使用路径最后一段检查扩展名，不探测文件真实二进制内容。
     *
     * @param name 客户端提供的文件名
     * @param allowedExtensions 允许的扩展名小写集合（如 txt, md, pdf）
     * @return 裁剪首尾空白后的原始文件名
     * @throws FileValidationException 文件名为空白、超过 255 字符或扩展名不在允许集合中
     */
    public static String requireName(String name, Set<String> allowedExtensions) {
        if (name == null || name.isBlank()) {
            throw new FileValidationException("文件名不能为空");
        }
        String original = name.trim();
        if (original.length() > 255) {
            throw new FileValidationException("文件名不能超过 255 字符");
        }

        String basename = basename(original);
        int dot = basename.lastIndexOf('.');
        String extension = dot < 0 ? "" : basename.substring(dot + 1).toLowerCase(Locale.ROOT);
        boolean allowed =
                allowedExtensions.stream()
                        .map(value -> value.trim().toLowerCase(Locale.ROOT))
                        .anyMatch(extension::equals);
        if (!allowed) {
            throw new FileValidationException("文件扩展名不允许");
        }
        return original;
    }

    /**
     * 规范化 MIME 类型，去除参数（如 charset）并统一转为小写。
     *
     * <p>若未提供 MIME 或去除参数后为空白，默认使用 application/octet-stream，长度限制为 255 字符。
     *
     * @param mime 客户端声明的 Content-Type
     * @return 规范化后的小写 MIME 类型
     * @throws FileValidationException MIME 类型去除参数后超过 255 字符
     */
    public static String normalizeMime(String mime) {
        String value = mime == null ? "" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (value.length() > 255) {
            throw new FileValidationException("MIME 不能超过 255 字符");
        }
        return value.isBlank() ? "application/octet-stream" : value;
    }

    /**
     * 为新上传的文件生成标准对象存储 Key。
     *
     * <p>规则为：{prefix}/{yyyy/MM/dd}/{uuid}-{sanitizedFilename}。
     * 仅支持新上传文件；Java 字符长度不超过 512，UTF-8 编码字节数不超过 1023 字节。
     *
     * @param prefix 配置的存储前缀
     * @param name 文件原始名称
     * @param now 当前 UTC 时间戳
     * @return 生成的对象存储 Key
     * @throws FileValidationException 对象 Key 字符长度超过 512 或 UTF-8 字节超过 1023
     */
    public static String objectKey(String prefix, String name, Instant now) {
        String filename =
                basename(name)
                        .trim()
                        .replaceAll("[^\\p{L}\\p{N}._-]", "_")
                        .replace("..", "_")
                        .replaceFirst("^\\.+", "");
        if (filename.isBlank()) {
            filename = "file";
        }

        // 日期固定使用 UTC，UUID 让同名上传使用独立对象；超限时不截断名称。
        String key = prefix + "/" + UTC_DATE.format(now) + "/" + UUID.randomUUID() + "-" + filename;
        // 512 是原有 Java 字符长度边界；1023 是两个厂商共同采用的 UTF-8 字节预算。
        if (key.length() > 512) {
            throw new FileValidationException("对象 Key 超过长度限制");
        }
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_OBJECT_KEY_UTF8_BYTES) {
            throw new FileValidationException("对象 Key 的 UTF-8 长度不能超过 1023 字节");
        }
        return key;
    }

    /**
     * 提取路径中的文件名末段，统一处理 Windows 和 Linux 路径分隔符。
     *
     * @param name 包含或不包含路径的文件名
     * @return 路径末段文件名
     */
    private static String basename(String name) {
        String value = name.replace('\\', '/');
        return value.substring(value.lastIndexOf('/') + 1);
    }
}
