package com.item.aisummary.application.knowledge.service;

import com.item.aisummary.application.knowledge.command.SaveKnowledgeChunkCommand;
import com.item.aisummary.application.knowledge.port.KnowledgeChunkRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.domain.knowledge.enums.RevisionStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.exception.KnowledgeNotFoundException;
import com.item.aisummary.domain.knowledge.model.KnowledgeChunk;
import com.item.aisummary.domain.knowledge.rule.KnowledgeRules;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** 只保存调用方提供的片段，不读取文件或执行解析、切片与向量化。 */
public class KnowledgeChunkService {
    private final KnowledgeRevisionRepository revisions;
    private final KnowledgeChunkRepository chunks;
    private final Clock clock;
    public KnowledgeChunkService(KnowledgeRevisionRepository revisions, KnowledgeChunkRepository chunks, Clock clock) {
        this.revisions = revisions; this.chunks = chunks; this.clock = clock;
    }

    /** 通过 Spring 装配的服务调用；版本锁、插入和计数更新共同提交或回滚。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<KnowledgeChunk> saveBatch(String revisionId, List<SaveKnowledgeChunkCommand> commands) {
        String id = KnowledgeRules.requireId(revisionId, "版本 ID");
        if (commands == null || commands.isEmpty()) throw new IllegalArgumentException("片段批次不能为空");
        if (commands.stream().anyMatch(command -> command == null)) throw new IllegalArgumentException("片段批次不能包含空项");
        var input = List.copyOf(commands);
        var sequences = new HashSet<Integer>();
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var prepared = new ArrayList<KnowledgeChunk>(input.size());
        for (var command : input) {
            if (!sequences.add(command.sequenceNo())) throw new IllegalArgumentException("批次内片段序号不能重复");
            prepared.add(new KnowledgeChunk(UUID.randomUUID().toString().replace("-", ""), id,
                command.sequenceNo(), command.content(), command.headingPath(), command.tokenCount(),
                command.startOffset(), command.endOffset(), command.pageStart(), command.pageEnd(), now));
        }
        var revision = revisions.findByIdForUpdate(id).orElseThrow(() -> new KnowledgeNotFoundException("文档版本"));
        if (revision.revisionStatus() != RevisionStatus.BUILDING) {
            throw new KnowledgeConflictException("REVISION_NOT_BUILDING", "只有 building 版本可以保存片段");
        }
        var saved = new ArrayList<KnowledgeChunk>(prepared.size());
        for (var chunk : prepared) saved.add(chunks.insert(chunk));
        // 追加批次后重新读取实际总数，不用输入批次数量覆盖此前已保存的片段数。
        int total = Math.toIntExact(chunks.countByRevisionId(id));
        if (revisions.updateChunkCount(id, total) != 1) throw new IllegalStateException("更新版本片段数量结果不符合预期");
        return List.copyOf(saved);
    }
    public KnowledgeChunk findById(String id) {
        return chunks.findById(KnowledgeRules.requireId(id, "片段 ID"))
            .orElseThrow(() -> new KnowledgeNotFoundException("知识片段"));
    }
    public List<KnowledgeChunk> findByRevisionId(String revisionId) {
        String id = KnowledgeRules.requireId(revisionId, "版本 ID");
        revisions.findById(id).orElseThrow(() -> new KnowledgeNotFoundException("文档版本"));
        return chunks.findByRevisionId(id);
    }
}
