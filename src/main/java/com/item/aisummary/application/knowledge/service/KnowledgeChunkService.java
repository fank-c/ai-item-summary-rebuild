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

/** 只保存调用方提供的片段，不读取文件或执行解析、切片与向量化。 */
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
     * 向指定 building 版本追加一批调用方已经准备好的片段。
     *
     * <p>先校验版本 ID 和非空批次，在构造每项片段时校验完整字段、生成新 ID、固定版本归属， 使用同一个微秒时间。所有输入验证均发生在数据库写入前；随后锁住目标版本， 确认仍为
     * building，再按输入顺序逐项插入，不修改既有片段正文。
     *
     * <p>插入后读取该版本实际总数，包含以前成功追加的批次，再更新 chunkCount。 版本行锁、插入和计数更新处于同一个 READ_COMMITTED 事务中，运行时失败回滚整批。
     * 同版本并发批次在行锁下串行执行；已存序号竞争由唯一约束报告，不重试。
     *
     * <p>必须通过 Spring 注入的服务调用，直接 new 或绕过代理不会获得该事务边界。 加入外层事务时，基础设施检查实际隔离级别；不兼容时写入前失败，兼容外层事务
     * 的回滚同样回滚本批次。本用例不解析文件、不切片、不计算 Token 或向量。
     *
     * @param revisionId 目标版本 ID，裁剪后非空白且最多 32 个 Java 字符
     * @param commands 非空片段批次，不含空项，批内序号唯一，不接受调用方指定片段 ID 或版本归属
     * @return 按输入顺序返回的已保存片段，不可修改，正文保持原样
     * @throws IllegalArgumentException ID、批次、正文、序号、Token、偏移或页码不合法
     * @throws KnowledgeNotFoundException 目标版本不存在
     * @throws KnowledgeConflictException 版本不是 building，或与已存片段序号冲突
     * @throws IllegalStateException 实际事务隔离级别不兼容，或仓储写入行数不符合合同
     * @throws ArithmeticException 实际总片段数超过 int 范围
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
     * 查询单个已保存片段，不展开所属版本或源文件。
     *
     * <p>先校验并裁剪片段 ID，再从仓储取得正文、标题及位置快照。 未找到时抛内部资源异常，不返回 null，也不重新推算未知位置或 Token 数。
     *
     * @param id 片段 ID，裁剪后非空白且最多 32 个 Java 字符
     * @return 已保存的片段快照
     * @throws IllegalArgumentException ID 不符合文本规则
     * @throws KnowledgeNotFoundException 片段不存在
     */
    public KnowledgeChunk findById(String id) {
        return chunks.findById(KnowledgeRules.requireId(id, "片段 ID"))
                .orElseThrow(() -> new KnowledgeNotFoundException("知识片段"));
    }

    /**
     * 查询指定版本的全部片段，由仓储按 sequenceNo 升序返回。
     *
     * <p>先确认规范化后的版本 ID 对应真实记录，再查询片段。 不要求版本仍为 building，不按活动版本过滤，不读取文件或操作向量。
     *
     * @param revisionId 版本 ID，查询前裁剪首尾空白
     * @return 升序片段列表；有效版本没有片段时为空列表
     * @throws IllegalArgumentException 版本 ID 不符合文本规则
     * @throws KnowledgeNotFoundException 版本不存在
     */
    public List<KnowledgeChunk> findByRevisionId(String revisionId) {
        String id = KnowledgeRules.requireId(revisionId, "版本 ID");
        revisions.findById(id).orElseThrow(() -> new KnowledgeNotFoundException("文档版本"));
        return chunks.findByRevisionId(id);
    }

    /**
     * 校验批次容器并复制为不可修改的输入快照，避免继续使用调用者的可变列表。
     *
     * <p>拒绝 null、空批次及空命令项；本步骤仅检查容器，不进行数据库访问。 序号唯一性与逐项领域字段检查在 prepareChunks 中完成，仍先于任何插入。
     *
     * @return 保留输入顺序的不可修改命令列表
     * @throws IllegalArgumentException 批次为空或包含空项
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
     * 将完整批次转换为新片段，在取得版本锁前完成所有可本地验证的字段检查。
     *
     * <p>拒绝批内重复序号；每项使用新生成的 32 位 UUID、入口确定的 revisionId 和共享时间。 KnowledgeChunk 构造器检查非负序号、非空白正文、Token
     * 与成组位置约束， 并保存标题列表快照。正文和未知位置保留原样，不计算或虚构元数据。
     *
     * <p>本步骤不访问仓储，任何项非法都会终止整批准备；与已存序号的冲突留给插入时 的数据库唯一约束处理，不用先查后写替代该约束。
     *
     * @return 按命令顺序准备的新片段列表，仅供本次保存流程使用
     * @throws IllegalArgumentException 批内序号重复或某项不满足领域字段规则
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
