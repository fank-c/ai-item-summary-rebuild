package com.item.aisummary.application.knowledge.processing;

import com.item.aisummary.domain.knowledge.rule.KnowledgeRules;
import com.item.aisummary.domain.knowledge.value.Heading;
import java.util.List;

/**
 * 单个知识片段草稿模型。
 *
 * <p>表示切分策略产出的单条片段正文及其可靠元数据。
 *
 * <p>设计要点：
 * <ul>
 *   <li>不包含数据库片段 ID、版本 ID 或父片段 ID；片段业务主键与两层父子关系由持久化保存入口统一分配和绑定。</li>
 *   <li>片段文本在保存时不被服务层强制截断，保留处理后的原样字符。</li>
 *   <li>标题路径、Token 数与来源位置仅在能可靠获得时提供，未知时保持为 null。</li>
 * </ul>
 *
 * @param content 片段正文文本（非空且非空白）
 * @param headingPath 从根标题到当前片段的有序标题路径快照；未知时为 null
 * @param tokenCount 分词器计算的 Token 数（必须非负）；未知时为 null
 * @param source 处理后全文中的精确字符偏移与页码定位；未知时为 null
 */
public record ChunkDraft(
        String content, List<Heading> headingPath, Integer tokenCount, SourceLocation source) {
    public ChunkDraft {
        KnowledgeRules.requireChunk(0, content, tokenCount, null, null, null, null);
        if (headingPath != null) {
            if (headingPath.stream().anyMatch(heading -> heading == null)) {
                throw new IllegalArgumentException("标题路径不能包含空项");
            }
            headingPath = List.copyOf(headingPath);
        }
    }

    /**
     * 避免在日志输出片段文本，仅标识是否包含来源位置。
     */
    @Override public String toString() {
        return "ChunkDraft[hasSource=" + (source != null) + "]";
    }
}
