package com.item.aisummary.application.knowledge.port;

import com.item.aisummary.application.knowledge.processing.ChunkGroupDraft;
import com.item.aisummary.application.knowledge.processing.ChunkingMode;
import com.item.aisummary.application.knowledge.processing.ChunkingOptions;
import com.item.aisummary.application.knowledge.processing.ParsedDocument;
import java.util.List;

/**
 * 文档切分策略能力端口（Port）。
 *
 * <p>切分逻辑独立于源文件格式，由 {@link com.item.aisummary.application.knowledge.processing.ChunkingMode} 路由：
 * <ul>
 *   <li>平铺模式（FLAT）：输出无父片段的纯检索块组（context 为 null，retrievals 包含普通块）。</li>
 *   <li>父子模式（PARENT_CHILD）：输出带上下文父块的分块组（context 为父块，retrievals 为子检索块），也允许为过短内容返回无父组。</li>
 * </ul>
 *
 * <p>实现约束：
 * <ul>
 *   <li>本接口是纯粹的内容转换合同，不分配数据库 UUID、不访问文件系统或网络、不写数据库或向量存储。</li>
 *   <li>必须尊重输入内容块中的不可跨越边界（boundaryBefore），避免把由分割线隔开的不同逻辑段落合并切分。</li>
 *   <li>必须控制父/子正文预算及总输出规模（不超过 options.maxNumChunks()）。</li>
 *   <li>来源位置（SourceLocation）只有在能够从解析结果中精确获得时才填写；无法可靠映射时保持 null，严禁将父块的范围直接复制为子块的范围。</li>
 * </ul>
 */
public interface KnowledgeChunker {
    /**
     * 该策略支持的分块模式。
     *
     * @return 分块模式枚举
     */
    ChunkingMode mode();

    /**
     * 将统一解析文档按指定配置切分为分块组草稿列表。
     *
     * @param document 统一结构化文档对象，按阅读顺序组织
     * @param options 本次分块的参数配置快照
     * @return 切分后的分块组草稿列表；必须非空且包含有效分块组
     */
    List<ChunkGroupDraft> chunk(ParsedDocument document, ChunkingOptions options);
}
