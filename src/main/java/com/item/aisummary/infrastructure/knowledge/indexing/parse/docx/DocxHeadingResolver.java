package com.item.aisummary.infrastructure.knowledge.indexing.parse.docx;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDecimalNumber;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;

/**
 * DOCX 段落与样式的标题层级（Heading Level）解析器。
 *
 * <p>判定优先级与约束：
 * <ul>
 *   <li>显式段落大纲层级优先：0..8 映射为 1..9 级标题；9 为显式正文并阻止样式回退；非法或溢出值明确拒绝。</li>
 *   <li>样式继承链解析：沿 basedOn 向上回溯，支持样式的 outlineLvl（0..8 为标题，9 为正文阻止继续回溯）或已知标题命名匹配。</li>
 *   <li>防御约束：严格拒绝继承环路与过深继承（上限 32）。</li>
 * </ul>
 */
final class DocxHeadingResolver {
    static final int MAX_STYLE_DEPTH = 32;

    private static final Pattern KNOWN_HEADING_PATTERN = Pattern.compile(
            "^(?:heading|标题)\\s*([1-9])$", Pattern.CASE_INSENSITIVE);

    private DocxHeadingResolver() {}

    /**
     * 解析给定段落的标题层级（1 ~ 9）。
     *
     * <p>优先级规则：
     * <ol>
     *   <li>段落内联属性优先：若段落显式声明了 {@code pPr/outlineLvl}，值 0..8 映射为 1..9 级标题；值为 9 代表显式声明为正文（Body Text），此时阻断回退直接返回 null；</li>
     *   <li>样式继承链回溯：若段落无显式大纲，沿样式的 {@code basedOn} 向上回溯；若样式显式声明了 {@code outlineLvl}，按相同规则处理；若样式名或样式 ID 匹配常见标题命名（如 Heading 1、标题 1），则映射为对应层级；</li>
     *   <li>防御约束：记录已访问样式 ID，若发现环路或深度超过 {@value #MAX_STYLE_DEPTH} 则抛异常拒绝。</li>
     * </ol>
     *
     * @param paragraph POI 段落对象
     * @param styles 文档样式表定义（可能为 null）
     * @return 1 ~ 9 的标题层级；若判定为普通正文则返回 null
     * @throws IllegalArgumentException 当样式存在循环继承、深度超限或大纲值越界时抛出
     */
    static Integer resolveHeadingLevel(XWPFParagraph paragraph, XWPFStyles styles) {
        CTP ctp = paragraph.getCTP();
        if (ctp != null && ctp.isSetPPr()) {
            CTPPr ppr = ctp.getPPr();
            if (ppr != null && ppr.isSetOutlineLvl()) {
                CTDecimalNumber outlineLvl = ppr.getOutlineLvl();
                if (outlineLvl != null && outlineLvl.getVal() != null) {
                    Integer level = parseOutlineLevel(outlineLvl.getVal());
                    if (level != null) {
                        return level == 9 ? null : level + 1;
                    }
                }
            }
        }

        String styleId = paragraph.getStyleID();
        if (styleId == null || styleId.isBlank()) {
            styleId = paragraph.getStyle();
        }
        if (styleId == null || styleId.isBlank()) {
            return null;
        }

        Set<String> visited = new HashSet<>();
        int depth = 0;
        String currentStyleId = styleId;

        while (currentStyleId != null && !currentStyleId.isBlank()) {
            if (!visited.add(currentStyleId)) {
                throw new IllegalArgumentException("DOCX 样式存在循环继承");
            }
            if (++depth > MAX_STYLE_DEPTH) {
                throw new IllegalArgumentException("DOCX 样式继承层级超过解析限制");
            }

            XWPFStyle style = styles != null ? styles.getStyle(currentStyleId) : null;
            if (style == null) {
                Integer known = matchKnownHeading(currentStyleId, null);
                if (known != null) {
                    return known;
                }
                break;
            }

            CTStyle ctStyle = style.getCTStyle();
            if (ctStyle != null && ctStyle.isSetPPr()) {
                CTPPrGeneral ppr = ctStyle.getPPr();
                if (ppr != null && ppr.isSetOutlineLvl()) {
                    CTDecimalNumber outlineLvl = ppr.getOutlineLvl();
                    if (outlineLvl != null && outlineLvl.getVal() != null) {
                        Integer level = parseOutlineLevel(outlineLvl.getVal());
                        if (level != null) {
                            return level == 9 ? null : level + 1;
                        }
                    }
                }
            }

            Integer known = matchKnownHeading(style.getStyleId(), style.getName());
            if (known != null) {
                return known;
            }

            currentStyleId = style.getBasisStyleID();
        }

        return null;
    }

    /**
     * 校验并转换 OOXML 大纲级别数值。
     *
     * @param rawVal BigInteger 形式的原始大纲数值
     * @return 0 ~ 9 的整数
     * @throws IllegalArgumentException 当数值小于 0 或大于 9 时抛出
     */
    private static Integer parseOutlineLevel(BigInteger rawVal) {
        if (rawVal.compareTo(BigInteger.ZERO) < 0 || rawVal.compareTo(BigInteger.valueOf(9)) > 0) {
            throw new IllegalArgumentException("DOCX 大纲层级非法");
        }
        return rawVal.intValueExact();
    }

    /**
     * 根据样式 ID 或样式名称尝试匹配已知的标准标题名称（支持中英文，如 "Heading 1", "标题 2"）。
     *
     * @param styleId 样式的 ID 标识
     * @param name 样式的展示名称（可包含逗号分隔的别名）
     * @return 匹配到的 1 ~ 9 标题层级；未匹配时返回 null
     */
    static Integer matchKnownHeading(String styleId, String name) {
        if (styleId != null) {
            Integer m = matchSingle(styleId);
            if (m != null) return m;
        }
        if (name != null) {
            for (String part : name.split(",")) {
                Integer m = matchSingle(part);
                if (m != null) return m;
            }
        }
        return null;
    }

    /**
     * 对单个字符串标识进行标题命名正则匹配。
     */
    private static Integer matchSingle(String str) {
        Matcher m = KNOWN_HEADING_PATTERN.matcher(str.trim());
        if (m.matches()) {
            return Integer.parseInt(m.group(1));
        }
        return null;
    }
}
