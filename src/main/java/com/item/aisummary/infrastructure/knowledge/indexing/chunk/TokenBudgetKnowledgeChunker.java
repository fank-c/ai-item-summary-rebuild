package com.item.aisummary.infrastructure.knowledge.indexing.chunk;

import com.item.aisummary.application.knowledge.indexing.chunk.ChunkDraft;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkGroupDraft;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingMode;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingOptions;
import com.item.aisummary.application.knowledge.indexing.chunk.KnowledgeChunker;
import com.item.aisummary.application.knowledge.indexing.parse.ContentBlock;
import com.item.aisummary.application.knowledge.indexing.parse.ContentBlockType;
import com.item.aisummary.application.knowledge.indexing.parse.ParsedDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 基于 Token 预算的生产级知识内容切分策略实现。
 *
 * <p>平铺模式（FLAT）与父子模式（PARENT_CHILD）共用相同的底层区间切分内核，不依赖源文件具体格式。
 *
 * <p>切分调度与业务规则：
 * <ul>
 *   <li>逻辑分组（logical run）：具有相同标题路径且中间无不可跨越硬边界（{@code boundaryBefore = true}）的连续文本块合并为一个运行单元。</li>
 *   <li>结构块隔离：代码块（CODE）与表格（TABLE）单独作为独立的逻辑单元处理，不与前后普通文本拼接；过长时仅按行切分或安全长度退化，不伪造 AST 行/列语义。</li>
 *   <li>模式行为：
 *     <ul>
 *       <li>FLAT 模式：直接按 {@code chunkSize} 与 {@code chunkOverlap} 切分为检索小块，输出无父片段组（{@code context = null}）。</li>
 *       <li>PARENT_CHILD 模式：先按 {@code parentChunkSize} 切出互不重叠（{@code overlap = 0}）的大上下文父块；再在每个父块范围内按 {@code chunkSize} 与 {@code chunkOverlap} 切出检索子块。
 *           若单一检索子块已完整覆盖父块全貌，则不额外冗余存储相同的父块正文（此时 {@code context = null}）。</li>
 *     </ul>
 *   </li>
 *   <li>父块与硬边界重置重叠窗口，确保跨父块或跨硬边界时不产生重叠。</li>
 *   <li>Token 计数基于本地 JTokkit CL100K_BASE 编码，不访问外部网络或模型服务。</li>
 * </ul>
 */
public final class TokenBudgetKnowledgeChunker implements KnowledgeChunker {
    /** 当前实例只决定输出拓扑；具体 Token 预算由调用时传入的 ChunkingOptions 决定。 */
    private final ChunkingMode mode;

    /** 负责对最终正文计数，并为超长文本寻找预算内的安全前缀。 */
    private final JTokkitTokenBudget tokens = new JTokkitTokenBudget();

    /** 只操作 SourceMappedText 的局部 UTF-16 区间，不直接生成或改写正文。 */
    private final TokenRangeSplitter splitter = new TokenRangeSplitter(tokens);

    /**
     * 构造切分策略实例。
     *
     * @param mode 切分模式（FLAT 或 PARENT_CHILD）
     * @throws IllegalArgumentException 当模式为 null 时抛出
     */
    public TokenBudgetKnowledgeChunker(ChunkingMode mode) {
        if (mode == null) throw new IllegalArgumentException("切分模式不能为空");
        this.mode = mode;
    }

    @Override public ChunkingMode mode() { return mode; }

    /**
     * 将解析文档切分为分块组草稿列表。
     *
     * @param document 统一解析文档
     * @param options 切分配置快照
     * @return 分块组列表（非空）
     * @throws IllegalArgumentException 当文档为空、无可切分正文或切片总数超出配置限制时抛出
     */
    @Override
    public List<ChunkGroupDraft> chunk(ParsedDocument document, ChunkingOptions options) {
        if (document == null || options == null) throw new IllegalArgumentException("切分输入和选项不能为空");

        var groups = new ArrayList<ChunkGroupDraft>();
        var run = new ArrayList<ContentBlock>();
        int count = 0;
        for (var block : document.blocks()) {
            boolean structured = block.type() == ContentBlockType.CODE || block.type() == ContentBlockType.TABLE;

            // run 是可以互相合并的“逻辑连续范围”。标题路径变化和显式硬边界都会先结束旧范围，
            // 这样一个 ChunkDraft 不会同时混入两个章节的标题语义；结构块还会额外强制独立。
            if (!run.isEmpty() && (block.boundaryBefore() || structured
                    || !Objects.equals(run.getFirst().headingPath(), block.headingPath()))) {
                count += appendRun(run, options, groups, options.maxNumChunks() - count);
                run.clear();
            }

            run.add(block);
            if (structured) {
                // CODE/TABLE 不与前后普通文本合并。appendRun 内部仍允许它们在超预算时按换行或安全长度退化。
                count += appendRun(run, options, groups, options.maxNumChunks() - count);
                run.clear();
            }
        }
        if (!run.isEmpty()) {
            count += appendRun(run, options, groups, options.maxNumChunks() - count);
        }
        if (groups.isEmpty()) throw new IllegalArgumentException("文档没有可切分的有效正文");
        return List.copyOf(groups);
    }

    /**
     * 将一个逻辑连续内容块序列按当前分块模式切分并追加到输出分块组列表中。
     */
    private int appendRun(List<ContentBlock> blocks, ChunkingOptions options, List<ChunkGroupDraft> output, int remaining) {
        // SourceMappedText 同时保存拼接后的工作文本和“工作文本区间 -> 输入 ContentBlock”的映射。
        // 因此后续切分始终先得到区间，再由 source.draft() 生成正文及其元数据，避免用 indexOf() 反查重复正文。
        var source = new SourceMappedText(blocks, options.keepSeparator());
        String text = source.text();
        int count = 0;

        if (mode == ChunkingMode.FLAT) {
            // FLAT 中每个区间就是一个独立检索组，不创建 CONTEXT 父块。
            var ranges = split(source, 0, text.length(), options.chunkSize(), options.chunkOverlap(), options, remaining);
            for (var range : ranges) {
                output.add(new ChunkGroupDraft(null, List.of(source.draft(range, tokens))));
                count++;
            }
        } else {
            // PARENT_CHILD 分两层执行同一个区间内核：先切不重叠父范围，再在每个父范围内切子范围。
            // 父范围不重叠，避免同一上下文被重复保存；子范围的 overlap 只在当前父范围内生效。
            var parents = split(source, 0, text.length(), options.parentChunkSize(), 0, options, remaining);
            for (var parent : parents) {
                var childRanges = split(source, parent.start(), parent.end(), options.chunkSize(), options.chunkOverlap(), options, remaining - count);
                List<ChunkDraft> children = childRanges.stream().map(range -> source.draft(range, tokens)).toList();
                if (children.isEmpty()) continue;

                // 短父范围只有一个完整子块时，子块已经同时满足检索和上下文用途；不重复写入相同正文。
                ChunkDraft context = children.size() == 1 && childRanges.getFirst().equals(parent)
                        ? null : source.draft(parent, tokens);
                long next = (long) count + children.size() + (context == null ? 0 : 1);
                if (next > remaining) {
                    // remaining 同时为 CONTEXT 和 RETRIEVAL 保留预算，不能只按子块数量判断。
                    throw new IllegalArgumentException("总片段数超过构建限制");
                }
                count = (int) next;
                output.add(new ChunkGroupDraft(context, children));
            }
        }
        return count;
    }

    /**
     * 委托底层区间切分器执行局部字符区间的切分。
     */
    private List<TokenRangeSplitter.Range> split(SourceMappedText source, int from, int to, int maxTokens,
            int overlap, ChunkingOptions options, int remaining) {
        // from/to 是 source.text() 的 UTF-16 半开区间；splitter 不负责标题、页码或绝对偏移推导。
        // remaining 传给底层作为硬上限，任何模式都不能先生成超出 maxNumChunks 的结果再交给外层拒绝。
        return splitter.split(source.text(), from, to, maxTokens, overlap, options.minChunkSizeChars(),
                options.punctuationMarks(), source.lineOnly(), remaining);
    }
}
