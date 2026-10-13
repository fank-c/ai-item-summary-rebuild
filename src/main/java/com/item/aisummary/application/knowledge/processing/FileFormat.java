package com.item.aisummary.application.knowledge.processing;

import java.util.Locale;

/**
 * 支持的源文件格式枚举。
 *
 * <p>用于前置格式校验与解析器策略路由。
 * 声明枚举值仅表示可接受的格式类型，不代表当前系统已具备生产解析实现。
 */
public enum FileFormat {
    /** 纯文本文件（.txt）。 */
    TXT,
    /** Markdown 文档（.md）。 */
    MD,
    /** PDF 文档（.pdf）。 */
    PDF,
    /** Office Word 文档（.docx，不支持旧版二进制 .doc）。 */
    DOCX;

    /**
     * 根据源文件原始展示名称的末段扩展名识别文件格式。
     *
     * <p>扩展名比较不区分大小写；无扩展名或未受支持的扩展名将抛出异常。
     *
     * @param originName 源文件原始文件名
     * @return 对应的文件格式枚举
     * @throws IllegalArgumentException 当文件名为空白、无扩展名或格式不被支持时抛出
     */
    public static FileFormat fromFileName(String originName) {
        if (originName == null || originName.isBlank()) {
            throw new IllegalArgumentException("源文件名称不能为空");
        }
        int dot = originName.lastIndexOf('.');
        if (dot < 0 || dot == originName.length() - 1) {
            throw new IllegalArgumentException("源文件格式不受支持");
        }
        return switch (originName.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "txt" -> TXT;
            case "md" -> MD;
            case "pdf" -> PDF;
            case "docx" -> DOCX;
            default -> throw new IllegalArgumentException("源文件格式不受支持");
        };
    }
}
