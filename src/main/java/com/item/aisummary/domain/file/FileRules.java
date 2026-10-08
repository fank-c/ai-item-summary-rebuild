package com.item.aisummary.domain.file;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 文件输入文本规则；只约束名称和 MIME，不识别实际内容格式。 */
public final class FileRules {
    private static final DateTimeFormatter UTC_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private FileRules() { }

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

    public static String normalizeMime(String mime) {
        String value = mime == null ? "" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (value.length() > 255) throw new FileValidationException("MIME 不能超过 255 字符");
        return value.isBlank() ? "application/octet-stream" : value;
    }

    public static String objectKey(String prefix, String name, Instant now) {
        String filename = basename(name).trim().replaceAll("[^\\p{L}\\p{N}._-]", "_").replace("..", "_");
        filename = filename.replaceFirst("^\\.+", "");
        if (filename.isBlank()) filename = "file";
        String key = prefix + "/" + UTC_DATE.format(now) + "/" + UUID.randomUUID() + "-" + filename;
        if (key.length() > 512) throw new FileValidationException("对象 Key 超过长度限制");
        return key;
    }

    private static String basename(String name) {
        String value = name.replace('\\', '/');
        return value.substring(value.lastIndexOf('/') + 1);
    }
}
