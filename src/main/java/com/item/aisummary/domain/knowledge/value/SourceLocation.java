package com.item.aisummary.domain.knowledge.value;

import com.item.aisummary.domain.knowledge.rule.KnowledgeRules;

/**
 * 可靠的来源位置信息值对象。
 *
 * <p>表示内容块或片段在源文档中的精确物理/字符定位：
 * <ul>
 *   <li>字符偏移（startOffset/endOffset）：指处理后全文中的 UTF-16 字符半开区间 `[start, end)`，不是源文件原始字节偏移，也不是父块内部偏移。</li>
 *   <li>页码（pageStart/pageEnd）：指源物理文档中的闭区间页码 `[start, end]`（从 1 开始）。</li>
 * </ul>
 *
 * <p>业务规则：
 * 偏移必须成对出现（同时为空或满足 `0 <= startOffset < endOffset`）；
 * 页码必须成对出现（同时为空或满足 `1 <= pageStart <= pageEnd`）。
 * 无法可靠确定时字段保持为 null，严禁使用推测值或将父块位置复制为子块位置。
 *
 * @param startOffset 处理后全文中的 UTF-16 起始字符偏移；未知时为 null
 * @param endOffset 处理后全文中的 UTF-16 结束字符偏移（不包含）；未知时为 null
 * @param pageStart 源文件中的起始页码（从 1 开始）；未知时为 null
 * @param pageEnd 源文件中的结束页码（包含该页）；未知时为 null
 */
public record SourceLocation(
        Integer startOffset, Integer endOffset, Integer pageStart, Integer pageEnd) {
    public SourceLocation {
        KnowledgeRules.requireLocation(startOffset, endOffset, pageStart, pageEnd);
    }
}
