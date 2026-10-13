package com.item.aisummary.application.knowledge.indexing.build;

import com.item.aisummary.application.knowledge.port.KnowledgeChunkRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.domain.knowledge.enums.ChunkRole;
import com.item.aisummary.domain.knowledge.enums.DocumentIndexStatus;
import com.item.aisummary.domain.knowledge.enums.RevisionStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.exception.KnowledgeNotFoundException;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocument;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocumentRevision;
import com.item.aisummary.domain.knowledge.rule.KnowledgeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识文档构建版本的数据库生命周期边界。
 *
 * <p>每个入口均使用 READ_COMMITTED 事务，锁定与真实连接隔离级别由持久化适配器验证；
 * 外部文件、解析和向量操作不属于本服务。
 */
public class KnowledgeBuildLifecycleService {
    private static final String CLEANUP_FAILURE_SUFFIX = "；向量清理失败";
    private static final String CLEANUP_FAILURE_MESSAGE = "向量清理失败";
    private static final int FAILURE_MESSAGE_LIMIT = 512;

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeRevisionRepository revisions;
    private final KnowledgeChunkRepository chunks;
    private final Clock clock;

    /**
     * 构造知识库构建生命周期服务。
     *
     * @param documents 知识文档仓储
     * @param revisions 知识文档版本仓储
     * @param chunks 知识片段仓储
     * @param clock 统一共享的微秒时钟源
     */
    public KnowledgeBuildLifecycleService(
            KnowledgeDocumentRepository documents,
            KnowledgeRevisionRepository revisions,
            KnowledgeChunkRepository chunks,
            Clock clock) {
        this.documents = documents;
        this.revisions = revisions;
        this.chunks = chunks;
        this.clock = clock;
    }

    /**
     * 锁定知识文档、确认无并发 BUILDING 版本，并分配递增的 revision_number 创建构建版本。
     *
     * <p>在 READ_COMMITTED 短事务中执行，不修改文档的活动版本指针（active_revision_id），
     * 返回包含文档快照与新建版本的构建上下文。
     *
     * @param documentId 目标知识文档业务 ID
     * @return 包含当前文档快照与新建 building 版本的构建上下文
     * @throws KnowledgeNotFoundException 当指定文档不存在时抛出
     * @throws KnowledgeConflictException 当该文档已有构建进行中（BUILD_ALREADY_RUNNING）或版本号耗尽时抛出
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public KnowledgeBuildContext begin(String documentId) {
        String id = KnowledgeRules.requireId(documentId, "文档 ID");
        KnowledgeDocument document = lockDocument(id);

        if (revisions.findByDocumentId(id).stream()
                .anyMatch(revision -> revision.revisionStatus() == RevisionStatus.BUILDING)) {
            throw new KnowledgeConflictException("BUILD_ALREADY_RUNNING", "该文档已有构建正在进行");
        }

        int maximum = revisions.findMaxRevisionNumber(id);
        if (maximum == Integer.MAX_VALUE) {
            throw new KnowledgeConflictException("REVISION_NUMBER_EXHAUSTED", "文档版本号已达到上限");
        }

        Instant now = now();
        KnowledgeDocumentRevision revision = new KnowledgeDocumentRevision(
                UUID.randomUUID().toString().replace("-", ""),
                id,
                maximum + 1,
                RevisionStatus.BUILDING,
                null,
                0,
                null,
                null,
                null,
                now,
                now,
                null,
                null,
                null);
        return new KnowledgeBuildContext(document, revisions.insert(revision));
    }

    /**
     * 在独立短事务中核实全部片段行数、检索片段行数与文档快照，原子发布新版本并替代旧活动版本。
     *
     * <p>发布规则与一致性约束：
     * <ul>
     *   <li>锁定文档行与目标版本行，确保版本属于该文档且仍处于 BUILDING 状态。</li>
     *   <li>校验文档源文件与知识库归属未变化，校验活动版本指针与构建开始时持有的一致。</li>
     *   <li>从数据库统计实际总行数与检索角色行数，必须严格等于预期值。</li>
     *   <li>将新版本流转为 READY，更新 completed_at、published_at 与 source_sha256。</li>
     *   <li>若存在旧活动版本，将其流转为 SUPERSEDED。</li>
     *   <li>更新文档表的 active_revision_id 指向新版本，并将 index_status 设为 INDEXED。</li>
     * </ul>
     *
     * @param context 构建启动时获取的上下文
     * @param sourceSha256 本次实际读取并核对的源文件 SHA-256 摘要
     * @param expectedTotal 预期的全角色片段总数
     * @param expectedRetrieval 预期的检索片段数（RETRIEVAL）
     * @throws IllegalArgumentException 当摘要格式或片段计数参数不合法时抛出
     * @throws KnowledgeConflictException 当快照发生并发漂移或片段计数不匹配时抛出
     * @throws IllegalStateException 当数据库更新受影响行数不符合预期时抛出
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void publish(
            KnowledgeBuildContext context,
            String sourceSha256,
            int expectedTotal,
            int expectedRetrieval) {
        requireContext(context);
        if (sourceSha256 == null || !sourceSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("源文件摘要格式无效");
        }
        if (expectedTotal <= 0 || expectedRetrieval <= 0 || expectedRetrieval > expectedTotal) {
            throw new IllegalArgumentException("片段数量不符合预期");
        }

        String documentId = context.document().id();
        KnowledgeDocument document = lockDocument(documentId);
        KnowledgeDocumentRevision revision = lockRevision(context.revision().id());
        requireRevisionOwner(revision, documentId);
        requireBuilding(revision);
        requireDocumentSnapshot(document, context.document());
        if (!sameNullable(document.activeRevisionId(), context.previousActiveRevisionId())) {
            throw new KnowledgeConflictException("BUILD_SNAPSHOT_CHANGED", "构建期间文档活动版本已变化");
        }

        long actualTotal = chunks.countByRevisionId(revision.id());
        long actualRetrieval = chunks.countByRevisionIdAndRole(revision.id(), ChunkRole.RETRIEVAL);
        if (actualTotal != expectedTotal
                || actualRetrieval != expectedRetrieval
                || revision.chunkCount() != expectedTotal) {
            throw new KnowledgeConflictException("BUILD_CHUNK_COUNT_MISMATCH", "构建片段数量与保存结果不一致");
        }

        KnowledgeDocumentRevision previous = null;
        String previousId = document.activeRevisionId();
        if (previousId != null) {
            if (previousId.equals(revision.id())) {
                throw new KnowledgeConflictException("BUILD_SNAPSHOT_CHANGED", "构建版本不能覆盖自身活动指针");
            }
            previous = lockRevision(previousId);
            requireRevisionOwner(previous, documentId);
            if (previous.revisionStatus() != RevisionStatus.READY || previous.publishedAt() == null) {
                throw new KnowledgeConflictException("ACTIVE_REVISION_INVALID", "文档活动版本状态不符合预期");
            }
            if (document.indexStatus() != DocumentIndexStatus.INDEXED) {
                throw new KnowledgeConflictException("ACTIVE_REVISION_INVALID", "文档索引状态与活动版本不一致");
            }
        } else if (document.indexStatus() == DocumentIndexStatus.INDEXED) {
            throw new KnowledgeConflictException("ACTIVE_REVISION_INVALID", "文档索引状态缺少活动版本");
        }

        Instant completedAt = now();
        if (revisions.complete(revision.id(), documentId, expectedTotal, sourceSha256, completedAt) != 1) {
            throw new IllegalStateException("发布文档版本结果不符合预期");
        }
        if (previous != null && revisions.supersede(previous.id(), documentId) != 1) {
            throw new IllegalStateException("替代旧文档版本结果不符合预期");
        }
        if (documents.updateIndexState(
                        documentId,
                        revision.id(),
                        DocumentIndexStatus.INDEXED,
                        null,
                        null,
                        completedAt)
                != 1) {
            throw new IllegalStateException("更新文档活动版本结果不符合预期");
        }
    }

    /**
     * 将仍在构建的版本标记为失败（FAILED），并更新文档的失败状态与安全摘要。
     *
     * <p>若为首次构建失败，文档状态变为 FAILED；若文档已有旧的可用活动版本（重建失败），
     * 文档依然保持 INDEXED 状态，保留旧活动版本指针，仅记录最近失败编码与脱敏摘要。
     * 若版本已经发布（非 BUILDING 状态），本方法直接忽略，严禁反向覆盖已成功的发布状态。
     *
     * @param context 构建上下文
     * @param stage 失败发生的阶段枚举名称
     * @param code 内部安全错误码
     * @param safeMessage 固定的脱敏安全提示文本
     * @return 是否成功将 BUILDING 版本标记为 FAILED
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean fail(KnowledgeBuildContext context, String stage, String code, String safeMessage) {
        requireContext(context);
        requireSafeFailure(stage, "失败阶段");
        requireSafeFailure(code, "失败编码");
        if (!KnowledgeBuildStage.valueOf(stage).safeMessage().equals(safeMessage)) {
            throw new IllegalArgumentException("失败摘要必须使用固定安全消息");
        }

        String documentId = context.document().id();
        KnowledgeDocument document = lockDocument(documentId);
        KnowledgeDocumentRevision revision = lockRevision(context.revision().id());
        requireRevisionOwner(revision, documentId);
        // 发布已成功时失败回调不得把 ready 版本或新活动指针反向覆盖。
        if (revision.revisionStatus() != RevisionStatus.BUILDING) {
            return false;
        }
        if (!sameNullable(document.activeRevisionId(), context.previousActiveRevisionId())) {
            throw new KnowledgeConflictException("BUILD_SNAPSHOT_CHANGED", "构建期间文档活动版本已变化");
        }

        Instant failedAt = now();
        if (revisions.fail(revision.id(), stage, code, safeMessage, failedAt) != 1) {
            throw new IllegalStateException("记录文档构建失败结果不符合预期");
        }
        DocumentIndexStatus status = document.activeRevisionId() == null
                ? DocumentIndexStatus.FAILED
                : DocumentIndexStatus.INDEXED;
        if (documents.updateIndexState(
                        documentId,
                        document.activeRevisionId(),
                        status,
                        code,
                        safeMessage,
                        failedAt)
                != 1) {
            throw new IllegalStateException("更新文档构建失败状态结果不符合预期");
        }
        return true;
    }

    /**
     * 记录非活动版本（FAILED 或 SUPERSEDED）的向量数据已在向量库完成物理清理（写入 cleaned_at）。
     *
     * @param documentId 知识文档 ID
     * @param revisionId 目标版本 ID（严禁传入当前文档的活动版本）
     * @throws KnowledgeNotFoundException 文档或版本不存在
     * @throws KnowledgeConflictException 试图清理当前活动版本或版本状态不允许清理
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markCleaned(String documentId, String revisionId) {
        KnowledgeDocument document = lockDocument(KnowledgeRules.requireId(documentId, "文档 ID"));
        KnowledgeDocumentRevision revision = lockRevision(KnowledgeRules.requireId(revisionId, "版本 ID"));
        requireRevisionOwner(revision, document.id());
        requireNonActiveCleanupTarget(document, revision);
        if (revision.cleanedAt() != null) {
            return;
        }
        if (revisions.markCleaned(revision.id(), now()) != 1) {
            throw new IllegalStateException("记录向量清理完成结果不符合预期");
        }
    }

    /**
     * 记录非活动版本的向量清理失败事实（在 failure_message 中追加固定后缀）。
     *
     * <p>保留版本原有的失败阶段与编码；若此前无失败信息，则记录阶段为 CLEANUP、编码为 VECTOR_CLEANUP_FAILED。
     *
     * @param documentId 知识文档 ID
     * @param revisionId 目标版本 ID
     * @throws KnowledgeConflictException 该版本已标记清理完成或试图操作活动版本
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordCleanupFailure(String documentId, String revisionId) {
        KnowledgeDocument document = lockDocument(KnowledgeRules.requireId(documentId, "文档 ID"));
        KnowledgeDocumentRevision revision = lockRevision(KnowledgeRules.requireId(revisionId, "版本 ID"));
        requireRevisionOwner(revision, document.id());
        requireNonActiveCleanupTarget(document, revision);
        if (revision.cleanedAt() != null) {
            throw new KnowledgeConflictException("REVISION_ALREADY_CLEANED", "已清理版本不能再记录清理失败");
        }

        String stage = revision.failureStage() == null ? "CLEANUP" : revision.failureStage();
        String code = revision.failureCode() == null ? "VECTOR_CLEANUP_FAILED" : revision.failureCode();
        String message = appendCleanupFailure(revision.failureMessage());
        if (revisions.recordCleanupFailure(revision.id(), stage, code, message) != 1) {
            throw new IllegalStateException("记录向量清理失败结果不符合预期");
        }
    }

    private KnowledgeDocument lockDocument(String id) {
        return documents.findByIdForUpdate(id)
                .orElseThrow(() -> new KnowledgeNotFoundException("知识文档"));
    }

    private KnowledgeDocumentRevision lockRevision(String id) {
        return revisions.findByIdForUpdate(id)
                .orElseThrow(() -> new KnowledgeNotFoundException("文档版本"));
    }

    private static void requireContext(KnowledgeBuildContext context) {
        if (context == null || context.document() == null || context.revision() == null) {
            throw new IllegalArgumentException("构建上下文不能为空");
        }
        KnowledgeRules.requireId(context.document().id(), "文档 ID");
        KnowledgeRules.requireId(context.revision().id(), "版本 ID");
    }

    private static void requireDocumentSnapshot(
            KnowledgeDocument actual, KnowledgeDocument snapshot) {
        if (!actual.fileId().equals(snapshot.fileId())
                || !actual.knowledgeBaseId().equals(snapshot.knowledgeBaseId())) {
            throw new KnowledgeConflictException(
                    "BUILD_SNAPSHOT_CHANGED", "构建期间文档源文件或知识库归属已变化");
        }
    }

    private static void requireRevisionOwner(KnowledgeDocumentRevision revision, String documentId) {
        if (!documentId.equals(revision.knowledgeDocumentId())) {
            throw new KnowledgeConflictException("REVISION_DOCUMENT_MISMATCH", "文档版本归属不符合预期");
        }
    }

    private static void requireBuilding(KnowledgeDocumentRevision revision) {
        if (revision.revisionStatus() != RevisionStatus.BUILDING) {
            throw new KnowledgeConflictException("REVISION_NOT_BUILDING", "只有 building 版本可以发布");
        }
    }

    private static void requireNonActiveCleanupTarget(
            KnowledgeDocument document, KnowledgeDocumentRevision revision) {
        if (revision.id().equals(document.activeRevisionId())) {
            throw new KnowledgeConflictException("ACTIVE_REVISION_CLEANUP_FORBIDDEN", "不能清理文档当前活动版本");
        }
        if (revision.revisionStatus() != RevisionStatus.FAILED
                && revision.revisionStatus() != RevisionStatus.SUPERSEDED) {
            throw new KnowledgeConflictException("REVISION_CLEANUP_NOT_ALLOWED", "仅可清理失败或已替代版本");
        }
    }

    private static boolean sameNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static void requireSafeFailure(String value, String field) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException(field + "无效");
        }
    }

    private static String appendCleanupFailure(String existing) {
        if (existing == null || existing.isBlank()) {
            return CLEANUP_FAILURE_MESSAGE;
        }
        String combined = existing.endsWith(CLEANUP_FAILURE_SUFFIX)
                ? existing
                : existing + CLEANUP_FAILURE_SUFFIX;
        int codePoints = combined.codePointCount(0, combined.length());
        if (codePoints <= FAILURE_MESSAGE_LIMIT) {
            return combined;
        }
        int retained = FAILURE_MESSAGE_LIMIT - CLEANUP_FAILURE_SUFFIX.codePointCount(
                0, CLEANUP_FAILURE_SUFFIX.length());
        int end = combined.offsetByCodePoints(0, retained);
        return combined.substring(0, end) + CLEANUP_FAILURE_SUFFIX;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
