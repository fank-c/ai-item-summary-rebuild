package com.item.aisummary.application.knowledge.indexing.build;

import com.item.aisummary.application.auth.ActorUserIdRules;
import com.item.aisummary.application.auth.AuthenticationRequiredException;
import com.item.aisummary.application.file.content.FileContentService;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkGroupDraft;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingMode;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingOptions;
import com.item.aisummary.application.knowledge.indexing.chunk.KnowledgeChunkGroupWriter;
import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.application.knowledge.indexing.source.KnowledgeSourceException;
import com.item.aisummary.application.knowledge.indexing.source.KnowledgeSourceReader;
import com.item.aisummary.application.knowledge.indexing.source.PreparedSource;
import com.item.aisummary.application.knowledge.indexing.vector.KnowledgeVectorEntry;
import com.item.aisummary.application.knowledge.indexing.vector.KnowledgeVectorIndex;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.domain.knowledge.enums.ChunkRole;
import com.item.aisummary.domain.knowledge.exception.KnowledgeNotFoundException;
import com.item.aisummary.domain.knowledge.model.KnowledgeChunk;
import com.item.aisummary.domain.knowledge.rule.KnowledgeRules;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库构建编排应用服务（Orchestrator）。
 *
 * <p>负责协调从源文件读取、格式解析、内容切分、数据库片段保存、向量写入到版本发布的全生命周期流程。
 *
 * <p>核心架构与事务边界原则：
 * <ul>
 *   <li>构建主入口使用 {@code Propagation.NEVER} 传播属性，严禁外层数据库事务包裹整条链路。</li>
 *   <li>源文件读取、格式解析策略、切分策略与外部 Embedding/Qdrant 向量交互完全在数据库事务外部执行，不持有数据库连接。</li>
 *   <li>数据库写操作（创建构建版本、分批保存片段组、原子发布与状态流转）分别采用独立的短事务处理。</li>
 *   <li>解析策略由源文件扩展名路由；切分模式（FLAT 或 PARENT_CHILD）由内部调用显式指定，两者互不绑定。</li>
 *   <li>数据库批次以完整 {@link ChunkGroupDraft} 为单位组织（不拆开父子关系）；向量批次按精确配置大小独立凑批，并在末尾 flush 尾批。</li>
 * </ul>
 */
public class KnowledgeIndexBuildService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexBuildService.class);
    private final FileContentService files;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeProcessingRouter router;
    private final KnowledgeSourceReader sourceReader;
    private final KnowledgeChunkGroupWriter chunks;
    private final KnowledgeVectorIndex vectors;
    private final KnowledgeBuildLifecycleService lifecycle;
    private final ChunkingOptions options;
    private final int batchSize;

    /**
     * 构造知识库构建编排服务。
     *
     * @param files 文件内容与归属校验服务
     * @param documents 知识文档仓储
     * @param router 策略路由注册表（包含已注册的 parser 与 chunker）
     * @param sourceReader 原字节受控读取器
     * @param chunks 构建侧组式保存转换器；委托基础服务执行短事务
     * @param vectors 外部向量索引客户端端口
     * @param lifecycle 构建生命周期短事务服务
     * @param options 切分参数配置快照
     * @param batchSize 数据库组批与向量缓冲的目标批次大小（1 到 1024）
     */
    public KnowledgeIndexBuildService(
            FileContentService files, KnowledgeDocumentRepository documents, KnowledgeProcessingRouter router,
            KnowledgeSourceReader sourceReader, KnowledgeChunkGroupWriter chunks, KnowledgeVectorIndex vectors,
            KnowledgeBuildLifecycleService lifecycle, ChunkingOptions options, int batchSize) {
        if (batchSize < 1 || batchSize > 1024 || options == null) {
            throw new IllegalArgumentException("构建批次或分块配置无效");
        }
        this.files = files;
        this.documents = documents;
        this.router = router;
        this.sourceReader = sourceReader;
        this.chunks = chunks;
        this.vectors = vectors;
        this.lifecycle = lifecycle;
        this.options = options;
        this.batchSize = batchSize;
    }

    /**
     * 执行知识文档的默认全量构建（默认采用 FLAT 平铺切分模式）。
     *
     * @param actorUserId 显式调用方用户 ID（非空且最多 64 个 Unicode 码点）
     * @param documentId 目标知识文档业务 ID
     * @return 构建发布结果
     * @throws AuthenticationRequiredException 缺少调用者身份
     * @throws KnowledgeNotFoundException 文档不存在
     * @throws KnowledgeBuildException 构建各阶段发生错误
     */
    @Transactional(propagation = Propagation.NEVER)
    public KnowledgeBuildResult build(String actorUserId, String documentId) {
        return doBuild(actorUserId, documentId, ChunkingMode.FLAT);
    }

    /**
     * 执行知识文档的全量构建，并显式指定切分模式（FLAT 或 PARENT_CHILD）。
     *
     * @param actorUserId 显式调用方用户 ID
     * @param documentId 目标知识文档业务 ID
     * @param mode 显式指定的分块模式（FLAT 或 PARENT_CHILD）
     * @return 构建发布结果
     * @throws AuthenticationRequiredException 缺少调用者身份
     * @throws KnowledgeNotFoundException 文档不存在
     * @throws IllegalStateException 缺少对应格式解析策略或分块策略
     * @throws KnowledgeBuildException 构建各阶段发生错误
     */
    @Transactional(propagation = Propagation.NEVER)
    public KnowledgeBuildResult build(String actorUserId, String documentId, ChunkingMode mode) {
        return doBuild(actorUserId, documentId, mode);
    }

    /**
     * 内部主编排流程：先检查归属/策略，再在短事务中分配版本，随后在事务外读取、解析、切分、分批入库与向量化，最后原子发布。
     */
    private KnowledgeBuildResult doBuild(String actorUserId, String documentId, ChunkingMode mode) {
        if (actorUserId == null || actorUserId.isBlank()) throw new AuthenticationRequiredException();
        ActorUserIdRules.requireValid(actorUserId);
        if (mode == null) throw new IllegalArgumentException("分块模式不能为空");
        String id = KnowledgeRules.requireId(documentId, "文档 ID");
        var document = documents.findById(id).orElseThrow(() -> new KnowledgeNotFoundException("知识文档"));
        var metadata = files.requireOwnedProcessable(actorUserId, document.fileId());
        FileFormat format = FileFormat.fromFileName(metadata.originName());
        var parser = router.requireParser(format);
        var chunker = router.requireChunker(mode);
        // 归属、格式和缺失策略在创建版本前拒绝，不留下无意义的 building 记录。
        KnowledgeBuildContext context = lifecycle.begin(id);
        KnowledgeBuildStage stage = KnowledgeBuildStage.SOURCE_READ;
        try {
            if (!context.document().fileId().equals(document.fileId())) {
                throw new IllegalStateException("构建源文件关联已变化");
            }
            PreparedSource source = prepareSource(actorUserId, context, format);
            stage = KnowledgeBuildStage.PARSE;
            var parsed = parser.parse(source);
            if (parsed == null) throw new IllegalStateException("解析策略没有返回有效文档");
            stage = KnowledgeBuildStage.CHUNK;
            var groups = validateGroups(chunker.chunk(parsed, options), mode);
            int expectedTotal = Math.toIntExact(groups.stream().mapToLong(ChunkGroupDraft::chunkCount).sum());
            int expectedRetrieval = Math.toIntExact(groups.stream().mapToLong(group -> group.retrievals().size()).sum());
            stage = KnowledgeBuildStage.CHUNK_SAVE;
            saveAndIndex(context, source, groups);
            stage = KnowledgeBuildStage.PUBLISH;
            lifecycle.publish(context, source.sha256(), expectedTotal, expectedRetrieval);
            boolean oldCleaned = context.previousActiveRevisionId() == null
                    || cleanupOld(context.document().id(), context.previousActiveRevisionId());
            return new KnowledgeBuildResult(id, context.revision().id(), expectedTotal, expectedRetrieval, oldCleaned);
        } catch (RuntimeException exception) {
            // 只有本服务保存/写向量步骤的内部包装可以细化阶段；不信任策略异常自行声明的构建阶段。
            if (stage == KnowledgeBuildStage.CHUNK_SAVE && exception instanceof KnowledgeBuildException failure
                    && (failure.stage() == KnowledgeBuildStage.CHUNK_SAVE
                            || failure.stage() == KnowledgeBuildStage.VECTOR_WRITE)) {
                stage = failure.stage();
            }
            String code = stage == KnowledgeBuildStage.SOURCE_READ && exception instanceof KnowledgeSourceException sourceFailure
                    ? sourceFailure.code() : stage.name() + "_FAILED";
            String message = stage.safeMessage();
            boolean markedFailed = false;
            try {
                markedFailed = lifecycle.fail(context, stage.name(), code, message);
            } catch (RuntimeException ignored) {
                log.warn("知识库构建失败事实未能保存，需核对版本状态");
            }
            // 发布提交异常可能结果不明，不删除派生向量；失败事实无法落库时同样保留待核对。
            boolean cleaned = false;
            if (stage != KnowledgeBuildStage.PUBLISH && markedFailed) {
                cleaned = cleanupOld(context.document().id(), context.revision().id());
            } else if (stage == KnowledgeBuildStage.PUBLISH) {
                log.warn("知识库版本发布失败，派生向量保留待核对");
            }
            throw new KnowledgeBuildException(stage, code, message, cleaned, exception);
        }
    }

    /**
     * 打开远端文件内容流并委托读取器限量加载与校验。
     * 源资源在 try-with-resources 块中关闭，确保即使读取或校验失败也不会泄漏网络连接。
     */
    private PreparedSource prepareSource(String actor, KnowledgeBuildContext context, FileFormat format) {
        try (var resource = files.openForProcessing(actor, context.document().fileId())) {
            if (!context.document().fileId().equals(resource.fileId())
                    || FileFormat.fromFileName(resource.originName()) != format) {
                throw new IllegalStateException("打开资源与构建源不一致");
            }
            return sourceReader.read(resource, format);
        } catch (IOException exception) {
            throw new KnowledgeSourceException("SOURCE_READ_FAILED", "源文件读取失败", exception);
        }
    }

    /**
     * 校验分块策略输出的草稿组合法性。
     * FLAT 模式严禁返回父块；同时校验总片段数（所有角色）不超过 options.maxNumChunks()。
     */
    private List<ChunkGroupDraft> validateGroups(List<ChunkGroupDraft> groups, ChunkingMode mode) {
        if (groups == null || groups.isEmpty() || groups.stream().anyMatch(group -> group == null)) {
            throw new IllegalArgumentException("分块策略必须返回有效分块组");
        }
        var snapshot = List.copyOf(groups);
        if (mode == ChunkingMode.FLAT && snapshot.stream().anyMatch(group -> group.context() != null)) {
            throw new IllegalArgumentException("平铺策略不能返回上下文父块");
        }
        long total = snapshot.stream().mapToLong(ChunkGroupDraft::chunkCount).sum();
        if (total > options.maxNumChunks()) throw new IllegalArgumentException("总片段数超过构建限制");
        return snapshot;
    }

    /**
     * 将分块组按 batchSize 目标大小分批持久化到 MySQL，并解耦组织固定大小的向量缓冲批量推送到向量存储。
     */
    private void saveAndIndex(KnowledgeBuildContext context, PreparedSource source, List<ChunkGroupDraft> groups) {
        var vectorBuffer = new ArrayList<KnowledgeVectorEntry>(batchSize);
        var databaseBatch = new ArrayList<ChunkGroupDraft>();
        int batchCount = 0;
        int sequence = 0;
        for (var group : groups) {
            if (!databaseBatch.isEmpty() && (long) batchCount + group.chunkCount() > batchSize) {
                saveDatabaseBatch(context, source, sequence, databaseBatch, vectorBuffer);
                sequence += batchCount;
                databaseBatch.clear();
                batchCount = 0;
            }
            // 单组大于 batchSize 时独立提交，仍不拆开父子关系；总组规模受 maxNumChunks 限制。
            databaseBatch.add(group);
            batchCount += group.chunkCount();
        }
        if (!databaseBatch.isEmpty()) saveDatabaseBatch(context, source, sequence, databaseBatch, vectorBuffer);
        flushVectors(vectorBuffer);
    }

    /**
     * 执行一个数据库批次的片段组保存，并将成功提交的 RETRIEVAL 角色片段收集到向量缓冲中。
     */
    private void saveDatabaseBatch(KnowledgeBuildContext context, PreparedSource source, int sequence,
            List<ChunkGroupDraft> batch, List<KnowledgeVectorEntry> vectorBuffer) {
        List<KnowledgeChunk> saved;
        try {
            saved = chunks.saveGroups(context.revision().id(), sequence, List.copyOf(batch));
        } catch (RuntimeException exception) {
            throw stepFailure(KnowledgeBuildStage.CHUNK_SAVE, exception);
        }
        for (var chunk : saved) {
            if (chunk.chunkRole() == ChunkRole.RETRIEVAL) {
                vectorBuffer.add(new KnowledgeVectorEntry(context.document().knowledgeBaseId(),
                        context.document().id(), source.fileId(), source.originName(), source.mime(), source.sha256(), chunk));
                if (vectorBuffer.size() == batchSize) flushVectors(vectorBuffer);
            }
        }
    }

    /**
     * 将当前填满或收尾的向量缓冲区批量写入外部向量索引，并在成功后清空缓冲区。
     */
    private void flushVectors(List<KnowledgeVectorEntry> buffer) {
        if (buffer.isEmpty()) return;
        try { vectors.add(List.copyOf(buffer)); }
        catch (RuntimeException exception) { throw stepFailure(KnowledgeBuildStage.VECTOR_WRITE, exception); }
        buffer.clear();
    }

    /**
     * 清理指定版本在向量库中的派生数据，并原子记录清理事实。
     * 区分向量物理删除与写库标记两步；仅在向量删除失败时记录清理失败事实。
     */
    private boolean cleanupOld(String documentId, String revisionId) {
        try {
            vectors.deleteByRevisionId(revisionId);
        } catch (RuntimeException ignored) {
            try { lifecycle.recordCleanupFailure(documentId, revisionId); }
            catch (RuntimeException unavailable) { log.warn("知识库向量清理失败事实未能保存"); }
            log.warn("知识库非活动版本向量清理未完成");
            return false;
        }
        try {
            lifecycle.markCleaned(documentId, revisionId);
            return true;
        } catch (RuntimeException ignored) {
            // 外部删除已成功，写库失败不能改称向量删除失败；没有完成事实时返回保守结果。
            log.warn("知识库向量已删除，但清理完成事实未能保存");
            return false;
        }
    }

    private static KnowledgeBuildException stepFailure(KnowledgeBuildStage stage, RuntimeException cause) {
        return new KnowledgeBuildException(stage, stage.name() + "_FAILED", stage.safeMessage(), false, cause);
    }
}
