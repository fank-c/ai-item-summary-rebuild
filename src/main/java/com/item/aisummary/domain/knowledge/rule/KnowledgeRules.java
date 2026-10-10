package com.item.aisummary.domain.knowledge.rule;

/** 知识库输入规则；全文偏移与片段正文长度没有直接对应关系。 */
public final class KnowledgeRules {
    private KnowledgeRules() { }

    public static String requireName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("知识库名称不能为空");
        String name = value.trim();
        if (name.length() > 100) throw new IllegalArgumentException("知识库名称不能超过 100 个字符");
        return name;
    }

    public static String requireId(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
        String id = value.trim();
        if (id.length() > 32) throw new IllegalArgumentException(field + "不能超过 32 个字符");
        return id;
    }

    public static void requireChunk(int sequenceNo, String content, Integer tokenCount,
                                    Integer startOffset, Integer endOffset, Integer pageStart, Integer pageEnd) {
        if (sequenceNo < 0) throw new IllegalArgumentException("片段序号不能为负数");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("片段正文不能为空");
        if (tokenCount != null && tokenCount < 0) throw new IllegalArgumentException("Token 数量不能为负数");
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
