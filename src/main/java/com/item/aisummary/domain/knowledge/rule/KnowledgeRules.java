package com.item.aisummary.domain.knowledge.rule;

/** 知识库输入规则；全文偏移与片段正文长度没有直接对应关系。 */
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
     * 校验来源位置；未知范围允许为空，已知范围必须成对且满足全文偏移或页码约束。
     *
     * <p>只校验范围形状，不证明其来源可靠性，也不以片段正文长度限制全文偏移。
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
