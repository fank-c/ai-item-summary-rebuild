package com.item.aisummary.domain.knowledge.rule;

/**
 * 知识库业务校验规则集中工具类。
 *
 * <p>封装知识库名称、业务 UUID、知识片段及其位置信息的有效性断言规则。
 * 领域实体与值对象通过本类进行防腐校验，集中保持知识库各模型之间约束的一致性。
 */
public final class KnowledgeRules {
    private KnowledgeRules() {}

    /**
     * 规范化并校验知识库名称。
     *
     * <p>去除首尾空白，名称不能为空且长度不能超过 100 个字符。
     *
     * @param value 输入的知识库名称
     * @return 去除首尾空白后的有效名称
     * @throws IllegalArgumentException 名称为空白或长度超过 100 个字符
     */
    public static String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        String name = value.trim();
        if (name.length() > 100) {
            throw new IllegalArgumentException("知识库名称不能超过 100 个字符");
        }
        return name;
    }

    /**
     * 规范化内部业务 ID（如知识库 ID、文档 ID、版本 ID 等）。
     *
     * <p>去除首尾空白，ID 不能为空且长度不能超过 32 个字符。
     *
     * @param value 输入的业务标识字符串
     * @param field 字段名称，用于异常提示
     * @return 去除首尾空白后的有效 ID
     * @throws IllegalArgumentException ID 为空白或长度超过 32 个字符
     */
    public static String requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        String id = value.trim();
        if (id.length() > 32) {
            throw new IllegalArgumentException(field + "不能超过 32 个字符");
        }
        return id;
    }

    /**
     * 校验知识片段的业务有效性与字段成组约束。
     *
     * <p>规则包括：
     * <ul>
     *   <li>序号非负：sequenceNo &gt;= 0</li>
     *   <li>正文非空：content 不能为空白</li>
     *   <li>Token 数非负：tokenCount &gt;= 0（若提供）</li>
     *   <li>偏移成对：startOffset 与 endOffset 必须同时为 null 或同时非 null 且满足 0 &lt;= start &lt; end</li>
     *   <li>页码成对：pageStart 与 pageEnd 必须同时为 null 或同时非 null 且满足 1 &lt;= start &lt;= end</li>
     * </ul>
     *
     * @param sequenceNo 片段在版本内的序号
     * @param content 片段切分正文
     * @param tokenCount 估算或计算的 Token 数量，可选
     * @param startOffset 在源文档中的字符起始偏移，可选
     * @param endOffset 在源文档中的字符结束偏移，可选
     * @param pageStart 在源文档中的起始页码，可选
     * @param pageEnd 在源文档中的结束页码，可选
     * @throws IllegalArgumentException 任意约束不满足时抛出
     */
    public static void requireChunk(
            int sequenceNo,
            String content,
            Integer tokenCount,
            Integer startOffset,
            Integer endOffset,
            Integer pageStart,
            Integer pageEnd) {
        if (sequenceNo < 0) {
            throw new IllegalArgumentException("片段序号不能为负数");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("片段正文不能为空");
        }
        if (tokenCount != null && tokenCount < 0) {
            throw new IllegalArgumentException("Token 数量不能为负数");
        }
        requireLocation(startOffset, endOffset, pageStart, pageEnd);
    }

    /**
     * 校验来源位置范围的几何合法性与成对约束。
     *
     * <p>规则约束：
     * <ul>
     *   <li>偏移成对：字符偏移 {@code startOffset} 与 {@code endOffset} 必须同时为空，
     *       或者同时非空且满足 {@code 0 <= startOffset < endOffset}。偏移量为处理后全文的 UTF-16 字符半开区间，
     *       不受片段当前文本长度限制。</li>
     *   <li>页码成对：起始页码 {@code pageStart} 与结束页码 {@code pageEnd} 必须同时为空，
     *       或者同时非空且满足 {@code 1 <= pageStart <= pageEnd}。</li>
     * </ul>
     * 未知位置允许为 null，但不允许单边缺失或反向范围。
     *
     * @param startOffset 处理后全文中的 UTF-16 起始偏移；可选
     * @param endOffset 处理后全文中的 UTF-16 结束偏移（半开区间）；可选
     * @param pageStart 源文件中的起始页码（从 1 开始）；可选
     * @param pageEnd 源文件中的结束页码（包含该页）；可选
     * @throws IllegalArgumentException 当任意一项成对约束或取值范围不合法时抛出
     */
    public static void requireLocation(
            Integer startOffset, Integer endOffset, Integer pageStart, Integer pageEnd) {
        if ((startOffset == null) != (endOffset == null)
                || (startOffset != null && (startOffset < 0 || startOffset >= endOffset))) {
            throw new IllegalArgumentException("字符偏移必须同时为空或满足 0 <= start_offset < end_offset");
        }
        if ((pageStart == null) != (pageEnd == null)
                || (pageStart != null && (pageStart < 1 || pageStart > pageEnd))) {
            throw new IllegalArgumentException("页码必须同时为空或满足 1 <= page_start <= page_end");
        }
    }
}
