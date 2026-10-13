package com.item.aisummary.application.knowledge.indexing.chunk;

import com.item.aisummary.application.knowledge.command.SaveKnowledgeChunkCommand;
import com.item.aisummary.application.knowledge.service.KnowledgeChunkService;
import com.item.aisummary.domain.knowledge.enums.ChunkRole;
import com.item.aisummary.domain.knowledge.model.KnowledgeChunk;
import java.util.ArrayList;
import java.util.List;

/**
 * 构建侧的分块组保存转换器；基础片段服务不需要认识解析或分块草稿。
 *
 * <p>在事务外将完整组批次转换为保存命令，再一次调用注入的基础服务代理。
 * 版本锁、父引用校验、ID 分配、插入和计数更新仍由基础服务在同一短事务内执行。
 */
public final class KnowledgeChunkGroupWriter {
    private final KnowledgeChunkService chunks;

    public KnowledgeChunkGroupWriter(KnowledgeChunkService chunks) {
        this.chunks = chunks;
    }

    /**
     * 按父块优先的顺序分配连续全局序号，将整批组委托给基础保存入口。
     *
     * <p>只调用一次 {@link KnowledgeChunkService#saveBatch}，不拆组、不逐片段开启事务。
     * 调用方必须注入 Spring 管理的片段服务，不能自行创建事务服务。
     *
     * @param revisionId 目标 building 版本 ID
     * @param firstSequenceNo 本批次起始的版本内全局序号
     * @param groups 完整组批次，非空且不含空项
     * @return 已保存的全部角色片段，保持组及组内顺序
     */
    public List<KnowledgeChunk> saveGroups(
            String revisionId, int firstSequenceNo, List<ChunkGroupDraft> groups) {
        if (firstSequenceNo < 0 || groups == null || groups.isEmpty()
                || groups.stream().anyMatch(group -> group == null)) {
            throw new IllegalArgumentException("分块组和起始序号无效");
        }
        List<ChunkGroupDraft> input = List.copyOf(groups);
        long total = input.stream().mapToLong(ChunkGroupDraft::chunkCount).sum();
        if (total + firstSequenceNo > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("分块序号超出整数范围");
        }
        var commands = new ArrayList<SaveKnowledgeChunkCommand>();
        int sequence = firstSequenceNo;
        for (var group : input) {
            Integer parentSequence = null;
            if (group.context() != null) {
                parentSequence = sequence;
                commands.add(toCommand(group.context(), sequence++, ChunkRole.CONTEXT, null));
            }
            for (var child : group.retrievals()) {
                commands.add(toCommand(child, sequence++, ChunkRole.RETRIEVAL, parentSequence));
            }
        }
        return chunks.saveBatch(revisionId, List.copyOf(commands));
    }

    private static SaveKnowledgeChunkCommand toCommand(
            ChunkDraft draft, int sequence, ChunkRole role, Integer parentSequence) {
        var source = draft.source();
        return new SaveKnowledgeChunkCommand(parentSequence, role, sequence, draft.content(),
                draft.headingPath(), draft.tokenCount(),
                source == null ? null : source.startOffset(), source == null ? null : source.endOffset(),
                source == null ? null : source.pageStart(), source == null ? null : source.pageEnd());
    }
}
