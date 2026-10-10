package com.item.aisummary.application.knowledge.service;

import com.item.aisummary.application.knowledge.command.SaveKnowledgeChunkCommand;
import com.item.aisummary.application.knowledge.port.KnowledgeChunkRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.domain.knowledge.enums.RevisionStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.exception.KnowledgeNotFoundException;
import com.item.aisummary.domain.knowledge.model.KnowledgeChunk;
import com.item.aisummary.domain.knowledge.rule.KnowledgeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识片段（Chunk）管理应用服务。
 *
 * <p>负责批次片段的校验与持久化，并在事务内维护所属版本的累计片段计数。
 */
public class KnowledgeChunkService {
    private final KnowledgeRevisionRepository revisions;
    private final KnowledgeChunkRepository chunks;
    private final Clock clock;

    public KnowledgeChunkService(
            KnowledgeRevisionRepository revisions, KnowledgeChunkRepository chunks, Clock clock) {
        this.revisions = revisions;
        this.chunks = chunks;
        this.clock = clock;
    }

    /**
     * 向处于 building 状态的版本批量追加保存片段，并原子更新版本累计片段数。
     *
     * @param revisionId 目标版本 ID
     * @param commands 待追加的片段命令列表（批内序号唯一）
     * @return 保存成功的片段实体列表
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<KnowledgeChunk> saveBatch(
            String revisionId, List<SaveKnowledgeChunkCommand> commands) {
        String id = KnowledgeRules.requireId(revisionId, "版本 ID");
        List<SaveKnowledgeChunkCommand> input = validateCommands(commands);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<KnowledgeChunk> prepared = prepareChunks(id, input, now);

        var revision =
                revisions
                        .findByIdForUpdate(id)
                        .orElseThrow(() -> new KnowledgeNotFoundException("文档版本"));
        if (revision.revisionStatus() != RevisionStatus.BUILDING) {
            throw new KnowledgeConflictException("REVISION_NOT_BUILDING", "只有 building 版本可以保存片段");
        }

        List<KnowledgeChunk> saved = new ArrayList<>(prepared.size());
        for (KnowledgeChunk chunk : prepared) {
            saved.add(chunks.insert(chunk));
        }

        // 追加批次后重新读取实际总数，不能用当前输入数量覆盖已有片段数。
        int total = Math.toIntExact(chunks.countByRevisionId(id));
        if (revisions.updateChunkCount(id, total) != 1) {
            throw new IllegalStateException("更新版本片段数量结果不符合预期");
        }
        return List.copyOf(saved);
    }

    /**
     * 根据片段 ID 查询单个已持久化的知识片段详情。
     *
     * <p>仅查询片段元数据快照，不展开所属版本或源文件，未知位置或 Token 计数值保持为 null。
     *
     * @param id 片段唯一标识（32 位 UUID）
     * @return 匹配的知识片段实体
     * @throws IllegalArgumentException 当片段 ID 为空或格式非法时抛出
     * @throws KnowledgeNotFoundException 当对应片段记录不存在时抛出
     */
    public KnowledgeChunk findById(String id) {
        return chunks.findById(KnowledgeRules.requireId(id, "片段 ID"))
                .orElseThrow(() -> new KnowledgeNotFoundException("知识片段"));
    }

    /**
     * 查询指定版本下的全部知识片段列表。
     *
     * <p>查询前校验版本存在性；返回列表固定按 {@code sequence_no}（片段序号）升序排序。
     *
     * @param revisionId 目标版本唯一标识（32 位 UUID）
     * @return 升序排序的知识片段列表；若该版本尚无片段则返回空列表
     * @throws IllegalArgumentException 当版本 ID 为空或格式非法时抛出
     * @throws KnowledgeNotFoundException 当指定版本不存在时抛出
     */
    public List<KnowledgeChunk> findByRevisionId(String revisionId) {
        String id = KnowledgeRules.requireId(revisionId, "版本 ID");
        revisions.findById(id).orElseThrow(() -> new KnowledgeNotFoundException("文档版本"));
        return chunks.findByRevisionId(id);
    }

    /**
     * 校验批次命令容器合法性，并防御性复制为不可修改列表。
     *
     * @param commands 待校验的批次命令列表
     * @return 不可修改的命令列表快照
     * @throws IllegalArgumentException 当批次为 null、为空列表或包含 null 元素时抛出
     */
    private static List<SaveKnowledgeChunkCommand> validateCommands(
            List<SaveKnowledgeChunkCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new IllegalArgumentException("片段批次不能为空");
        }
        if (commands.stream().anyMatch(command -> command == null)) {
            throw new IllegalArgumentException("片段批次不能包含空项");
        }
        return List.copyOf(commands);
    }

    /**
     * 将命令批次转换为领域实体列表，并在内存中完成序号去重与字段合法性校验。
     *
     * <p>在获取数据库锁之前提前拦截批内重复序号等非法输入，降低数据库锁持有竞争。
     *
     * @param revisionId 所属版本 ID
     * @param commands 命令列表快照
     * @param now 当前统一微秒时间戳
     * @return 预组装完成的知识片段实体列表
     * @throws IllegalArgumentException 当批次内存在重复序号或单项字段不符合约束时抛出
     */
    private static List<KnowledgeChunk> prepareChunks(
            String revisionId, List<SaveKnowledgeChunkCommand> commands, Instant now) {
        var sequences = new HashSet<Integer>();
        var prepared = new ArrayList<KnowledgeChunk>(commands.size());
        for (SaveKnowledgeChunkCommand command : commands) {
            if (!sequences.add(command.sequenceNo())) {
                throw new IllegalArgumentException("批次内片段序号不能重复");
            }
            prepared.add(
                    new KnowledgeChunk(
                            UUID.randomUUID().toString().replace("-", ""),
                            revisionId,
                            command.sequenceNo(),
                            command.content(),
                            command.headingPath(),
                            command.tokenCount(),
                            command.startOffset(),
                            command.endOffset(),
                            command.pageStart(),
                            command.pageEnd(),
                            now));
        }
        return prepared;
    }
}
