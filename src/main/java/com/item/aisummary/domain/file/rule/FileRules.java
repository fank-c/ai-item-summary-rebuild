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
    private static final DateTimeFormatter UTC_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private FileRules() { }

    /** 保留裁剪后的展示名称；只用路径末段检查扩展名，不检查实际内容格式。 */
    public static String requireName(String name, Set<String> allowedExtensions) {
        if (name == null || name.isBlank()) throw new FileValidationException("文件名不能为空");
        String original = name.trim();
        if (original.length() > 255) throw new FileValidationException("文件名不能超过 255 字符");
        String basename = basename(original);
        int dot = basename.lastIndexOf('.');
        String extension = dot < 0 ? "" : basename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (allowedExtensions.stream().map(value -> value.trim().toLowerCase(Locale.ROOT)).noneMatch(extension::equals)) {
            throw new FileValidationException("文件扩展名不允许");
        }
        return original;
    }

    /** 去除 MIME 参数并统一大小写；缺失时用二进制类型，不推断实际格式。 */
    public static String normalizeMime(String mime) {
        String value = mime == null ? "" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (value.length() > 255) throw new FileValidationException("MIME 不能超过 255 字符");
        return value.isBlank() ? "application/octet-stream" : value;
    }

    /** 仅生成新上传的 Key；清理路径和特殊字符，不改写元数据中的展示名称。 */
    public static String objectKey(String prefix, String name, Instant now) {
        String filename = basename(name).trim().replaceAll("[^\\p{L}\\p{N}._-]", "_").replace("..", "_");
        filename = filename.replaceFirst("^\\.+", "");
        if (filename.isBlank()) filename = "file";
        // 日期固定使用 UTC，UUID 让同名上传使用独立对象；不在超限时截断名称。
        String key = prefix + "/" + UTC_DATE.format(now) + "/" + UUID.randomUUID() + "-" + filename;
        // 512 是原有 Java 字符长度边界；1023 是两个厂商共同采用的 UTF-8 字节预算。
        if (key.length() > 512) throw new FileValidationException("对象 Key 超过长度限制");
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_OBJECT_KEY_UTF8_BYTES) {
            throw new FileValidationException("对象 Key 的 UTF-8 长度不能超过 1023 字节");
        }
        return key;
    }

    private static String basename(String name) {
        String value = name.replace('\\', '/');
        return value.substring(value.lastIndexOf('/') + 1);
    }
}
