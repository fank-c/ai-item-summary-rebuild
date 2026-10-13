package com.item.aisummary.application.knowledge.indexing.parse;

import com.item.aisummary.domain.knowledge.value.Heading;
import com.item.aisummary.domain.knowledge.value.SourceLocation;
import java.util.List;

/**
 * 解析阶段产出的有序内容块。
 *
 * <p>表示源文档中一段尚未经过检索切分的语义内容单元（如一个自然段、一个标题或一个代码块）。
 * 块内容按从 0 连续的阅读顺序排列。
 *
 * @param ordinal 文档内按从 0 开始连续递增的阅读顺序编号
 * @param type 内容语义类别（TEXT/HEADING/CODE/TABLE）
 * @param text 该内容块的文本内容（非空且非空白）
 * @param headingPath 从根标题到该块的有序标题路径快照；若格式无法提供则为 null
 * @param source 精确的来源位置与页码信息；无法可靠映射时为 null
 * @param boundaryBefore 前方是否存在不可跨越的文档边界（如 Markdown 水平分割线），指导后续分块切分时不进行跨界合并
 */
public record ContentBlock(
        int ordinal, ContentBlockType type, String text, List<Heading> headingPath,
        SourceLocation source, boolean boundaryBefore) {
    public ContentBlock {
        if (ordinal < 0 || type == null || text == null || text.isBlank()) {
            throw new IllegalArgumentException("解析内容块的序号、类别或正文无效");
        }
        if (headingPath != null) {
            if (headingPath.stream().anyMatch(heading -> heading == null)) {
                throw new IllegalArgumentException("标题路径不能包含空项");
            }
            headingPath = List.copyOf(headingPath);
        }
    }

    /**
     * 避免在日志中输出大量文档正文，仅打印序号与类别。
     */
    @Override public String toString() {
        return "ContentBlock[ordinal=" + ordinal + ", type=" + type + "]";
    }
}
