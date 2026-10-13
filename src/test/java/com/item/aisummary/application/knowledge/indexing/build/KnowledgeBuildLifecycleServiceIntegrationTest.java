package com.item.aisummary.application.knowledge.indexing.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.item.aisummary.application.knowledge.port.KnowledgeChunkRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildContext;
import com.item.aisummary.domain.knowledge.enums.ChunkRole;
import com.item.aisummary.domain.knowledge.enums.DocumentIndexStatus;
import com.item.aisummary.domain.knowledge.enums.RevisionStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.model.KnowledgeChunk;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocument;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocumentRevision;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Lifecycle integration tests use the real MyBatis repositories and Spring transaction proxy on isolated H2. */
@SpringBootTest
@Import(KnowledgeBuildLifecycleServiceIntegrationTest.LifecycleTestConfiguration.class)
class KnowledgeBuildLifecycleServiceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00.123456Z");
    private static final String HASH = "a".repeat(64);

    @Autowired KnowledgeBuildLifecycleService lifecycle;
    @Autowired KnowledgeDocumentRepository documents;
    @Autowired KnowledgeRevisionRepository revisions;
    @Autowired KnowledgeChunkRepository chunks;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearKnowledgeTables() {
        jdbc.update("DELETE FROM knowledge_chunk");
        jdbc.update("DELETE FROM knowledge_document_revision");
        jdbc.update("DELETE FROM knowledge_document");
        jdbc.update("DELETE FROM knowledge_base");
    }

    @Test
    void beginCreatesBuildingRevisionWithoutMovingActivityAndRejectsAnotherBuild() {
        documents.insert(document("doc", null, DocumentIndexStatus.PENDING));

        KnowledgeBuildContext context = lifecycle.begin("doc");

        assertThat(context.document().activeRevisionId()).isNull();
        assertThat(context.revision().revisionNumber()).isEqualTo(1);
        assertThat(context.revision().revisionStatus()).isEqualTo(RevisionStatus.BUILDING);
        assertThat(context.revision().chunkCount()).isZero();
        assertThat(context.revision().createdAt()).isEqualTo(context.revision().startedAt());
        assertThat(context.revision().completedAt()).isNull();
        assertThat(documents.findById("doc").orElseThrow().activeRevisionId()).isNull();
        assertThatThrownBy(() -> lifecycle.begin("doc"))
                .isInstanceOf(KnowledgeConflictException.class)
                .extracting("code").isEqualTo("BUILD_ALREADY_RUNNING");
        assertThat(revisions.findByDocumentId("doc")).hasSize(1);
    }

    @Test
    void concurrentBeginsSerializeOnDocumentAndOnlyOneCreatesBuilding() throws Exception {
        documents.insert(document("doc", null, DocumentIndexStatus.PENDING));
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> attempt = () -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    lifecycle.begin("doc");
                    return true;
                } catch (KnowledgeConflictException conflict) {
                    assertThat(conflict.code()).isEqualTo("BUILD_ALREADY_RUNNING");
                    return false;
                }
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertThat(java.util.List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(10, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(revisions.findByDocumentId("doc")).hasSize(1);
    }

    @Test
    void publishChecksActualRoleCountsAndAtomicallySupersedesPreviousActiveRevision() {
        documents.insert(document("doc", "old", DocumentIndexStatus.INDEXED));
        revisions.insert(revision("old", "doc", 1, RevisionStatus.READY, 1, NOW, NOW));
        KnowledgeBuildContext context = lifecycle.begin("doc");
        chunks.insert(chunk("ctx", context.revision().id(), null, ChunkRole.CONTEXT, 0));
        chunks.insert(chunk("ret", context.revision().id(), "ctx", ChunkRole.RETRIEVAL, 1));
        assertThat(chunks.countByRevisionIdAndRole(context.revision().id(), ChunkRole.CONTEXT)).isEqualTo(1);
        assertThat(chunks.countByRevisionIdAndRole(context.revision().id(), ChunkRole.RETRIEVAL)).isEqualTo(1);
        assertThat(chunks.countByRevisionIdAndRole("other", ChunkRole.RETRIEVAL)).isZero();
        assertThat(revisions.updateChunkCount(context.revision().id(), 2)).isEqualTo(1);

        lifecycle.publish(context, HASH, 2, 1);

        var published = revisions.findById(context.revision().id()).orElseThrow();
        assertThat(published.revisionStatus()).isEqualTo(RevisionStatus.READY);
        assertThat(published.chunkCount()).isEqualTo(2);
        assertThat(published.sourceSha256()).isEqualTo(HASH);
        assertThat(published.completedAt()).isNotNull();
        assertThat(published.publishedAt()).isNotNull();
        assertThat(revisions.findById("old").orElseThrow().revisionStatus())
                .isEqualTo(RevisionStatus.SUPERSEDED);
        var document = documents.findById("doc").orElseThrow();
        assertThat(document.activeRevisionId()).isEqualTo(context.revision().id());
        assertThat(document.indexStatus()).isEqualTo(DocumentIndexStatus.INDEXED);
        assertThat(document.lastFailureCode()).isNull();
        assertThat(lifecycle.fail(context, "PUBLISH", "PUBLISH_FAILED", "知识版本发布失败，向量保留待核对"))
                .isFalse();
        assertThat(revisions.findById(context.revision().id()).orElseThrow().revisionStatus())
                .isEqualTo(RevisionStatus.READY);
        assertThat(documents.findById("doc").orElseThrow().activeRevisionId())
                .isEqualTo(context.revision().id());

        assertThatThrownBy(() -> lifecycle.markCleaned("doc", context.revision().id()))
                .isInstanceOf(KnowledgeConflictException.class);
        lifecycle.markCleaned("doc", "old");
        assertThat(revisions.findById("old").orElseThrow().cleanedAt()).isNotNull();
    }

    @Test
    void publishRejectsIncorrectExpectedCountsWithoutChangingBuildingRevision() {
        documents.insert(document("doc", null, DocumentIndexStatus.PENDING));
        KnowledgeBuildContext context = lifecycle.begin("doc");
        chunks.insert(chunk("retrieval", context.revision().id(), null, ChunkRole.RETRIEVAL, 0));
        assertThat(revisions.updateChunkCount(context.revision().id(), 1)).isEqualTo(1);

        assertThatThrownBy(() -> lifecycle.publish(context, HASH, 2, 1))
                .isInstanceOf(KnowledgeConflictException.class)
                .extracting("code").isEqualTo("BUILD_CHUNK_COUNT_MISMATCH");
        assertThat(revisions.findById(context.revision().id()).orElseThrow().revisionStatus())
                .isEqualTo(RevisionStatus.BUILDING);
        assertThat(documents.findById("doc").orElseThrow().activeRevisionId()).isNull();
    }

    @Test
    void publishRequiresPositiveTotalAndRetrievalCounts() {
        documents.insert(document("doc", null, DocumentIndexStatus.PENDING));
        KnowledgeBuildContext context = lifecycle.begin("doc");

        assertThatThrownBy(() -> lifecycle.publish(context, HASH, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lifecycle.publish(context, HASH, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lifecycle.publish(context, HASH, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(revisions.findById(context.revision().id()).orElseThrow().revisionStatus())
                .isEqualTo(RevisionStatus.BUILDING);
    }

    @Test
    void changedSourceOrKnowledgeBaseSnapshotRejectsPublish() {
        documents.insert(document("doc", null, DocumentIndexStatus.PENDING));
        KnowledgeBuildContext context = lifecycle.begin("doc");
        chunks.insert(chunk("snapshot-source-retrieval", context.revision().id(), null, ChunkRole.RETRIEVAL, 0));
        assertThat(revisions.updateChunkCount(context.revision().id(), 1)).isEqualTo(1);

        KnowledgeDocument original = context.document();
        var changedFile = new KnowledgeDocument(
                original.id(), original.knowledgeBaseId(), "different-file", original.status(),
                original.indexStatus(), original.activeRevisionId(), original.lastFailureCode(),
                original.lastFailureMessage(), original.createAt(), original.updateAt());
        KnowledgeBuildContext fileSnapshot = new KnowledgeBuildContext(changedFile, context.revision());
        assertThatThrownBy(() -> lifecycle.publish(fileSnapshot, HASH, 1, 1))
                .isInstanceOf(KnowledgeConflictException.class)
                .extracting("code").isEqualTo("BUILD_SNAPSHOT_CHANGED");

        var changedBase = new KnowledgeDocument(
                original.id(), "different-base", original.fileId(), original.status(),
                original.indexStatus(), original.activeRevisionId(), original.lastFailureCode(),
                original.lastFailureMessage(), original.createAt(), original.updateAt());
        KnowledgeBuildContext baseSnapshot = new KnowledgeBuildContext(changedBase, context.revision());
        assertThatThrownBy(() -> lifecycle.publish(baseSnapshot, HASH, 1, 1))
                .isInstanceOf(KnowledgeConflictException.class)
                .extracting("code").isEqualTo("BUILD_SNAPSHOT_CHANGED");
        assertThat(revisions.findById(context.revision().id()).orElseThrow().revisionStatus())
                .isEqualTo(RevisionStatus.BUILDING);
    }

    @Test
    void changedActivitySnapshotRejectsPublishBeforeLifecycleWrites() {
        documents.insert(document("doc", null, DocumentIndexStatus.PENDING));
        KnowledgeBuildContext context = lifecycle.begin("doc");
        chunks.insert(chunk("snapshot-retrieval", context.revision().id(), null, ChunkRole.RETRIEVAL, 0));
        assertThat(revisions.updateChunkCount(context.revision().id(), 1)).isEqualTo(1);
        documents.updateIndexState("doc", "external", DocumentIndexStatus.INDEXED, null, null, NOW);

        assertThatThrownBy(() -> lifecycle.publish(context, HASH, 1, 1))
                .isInstanceOf(KnowledgeConflictException.class)
                .extracting("code").isEqualTo("BUILD_SNAPSHOT_CHANGED");
        assertThat(revisions.findById(context.revision().id()).orElseThrow().revisionStatus())
                .isEqualTo(RevisionStatus.BUILDING);
        assertThat(documents.findById("doc").orElseThrow().activeRevisionId()).isEqualTo("external");
    }

    @Test
    void sqlFailureAfterRevisionUpdatesRollsBackPublicationAsOneTransaction() {
        documents.insert(document("doc", "old", DocumentIndexStatus.INDEXED));
        revisions.insert(revision("old", "doc", 1, RevisionStatus.READY, 0, NOW, NOW));
        KnowledgeBuildContext context = lifecycle.begin("doc");
        chunks.insert(chunk("rollback-retrieval", context.revision().id(), null, ChunkRole.RETRIEVAL, 0));
        assertThat(revisions.updateChunkCount(context.revision().id(), 1)).isEqualTo(1);
        jdbc.execute("ALTER TABLE knowledge_document ADD CONSTRAINT ck_test_reject_new_pointer CHECK (active_revision_id <> '"
                + context.revision().id() + "')");

        try {
            assertThatThrownBy(() -> lifecycle.publish(context, HASH, 1, 1))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(revisions.findById(context.revision().id()).orElseThrow().revisionStatus())
                    .isEqualTo(RevisionStatus.BUILDING);
            assertThat(revisions.findById("old").orElseThrow().revisionStatus())
                    .isEqualTo(RevisionStatus.READY);
            assertThat(documents.findById("doc").orElseThrow().activeRevisionId()).isEqualTo("old");
        } finally {
            jdbc.execute("ALTER TABLE knowledge_document DROP CONSTRAINT ck_test_reject_new_pointer");
        }
    }

    @Test
    void rejectsAnIncompatibleOuterTransactionUsingTheActualConnectionIsolation() {
        documents.insert(document("doc", null, DocumentIndexStatus.PENDING));
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        assertThatThrownBy(() -> outer.executeWithoutResult(ignored -> lifecycle.begin("doc")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READ_COMMITTED");
        assertThat(revisions.findByDocumentId("doc")).isEmpty();
    }

    @Test
    void firstBuildFailureAndRebuildFailurePreserveTheCorrectDocumentStateAndCleanupFacts() {
        documents.insert(document("first", null, DocumentIndexStatus.PENDING));
        KnowledgeBuildContext first = lifecycle.begin("first");
        assertThat(lifecycle.fail(first, "PARSE", "PARSE_FAILED", "源文件解析失败")).isTrue();
        var failedFirst = revisions.findById(first.revision().id()).orElseThrow();
        assertThat(failedFirst.revisionStatus()).isEqualTo(RevisionStatus.FAILED);
        assertThat(failedFirst.failureStage()).isEqualTo("PARSE");
        assertThat(failedFirst.failureCode()).isEqualTo("PARSE_FAILED");
        assertThat(documents.findById("first").orElseThrow().indexStatus())
                .isEqualTo(DocumentIndexStatus.FAILED);
        lifecycle.markCleaned("first", first.revision().id());
        assertThat(revisions.findById(first.revision().id()).orElseThrow().cleanedAt()).isNotNull();

        documents.insert(document("rebuild", "active", DocumentIndexStatus.INDEXED));
        revisions.insert(revision("active", "rebuild", 1, RevisionStatus.READY, 0, NOW, NOW));
        KnowledgeBuildContext rebuild = lifecycle.begin("rebuild");
        lifecycle.fail(rebuild, "VECTOR_WRITE", "VECTOR_WRITE_FAILED", "检索片段向量写入失败");
        var failedRebuild = revisions.findById(rebuild.revision().id()).orElseThrow();
        assertThat(failedRebuild.revisionStatus()).isEqualTo(RevisionStatus.FAILED);
        assertThat(failedRebuild.failureStage()).isEqualTo("VECTOR_WRITE");
        assertThat(failedRebuild.failureCode()).isEqualTo("VECTOR_WRITE_FAILED");
        assertThat(documents.findById("rebuild").orElseThrow().activeRevisionId()).isEqualTo("active");
        assertThat(documents.findById("rebuild").orElseThrow().indexStatus())
                .isEqualTo(DocumentIndexStatus.INDEXED);
        assertThat(documents.findById("rebuild").orElseThrow().lastFailureCode())
                .isEqualTo("VECTOR_WRITE_FAILED");

        lifecycle.recordCleanupFailure("rebuild", rebuild.revision().id());
        var cleanupFailed = revisions.findById(rebuild.revision().id()).orElseThrow();
        assertThat(cleanupFailed.failureStage()).isEqualTo("VECTOR_WRITE");
        assertThat(cleanupFailed.failureCode()).isEqualTo("VECTOR_WRITE_FAILED");
        assertThat(cleanupFailed.failureMessage()).isEqualTo("检索片段向量写入失败；向量清理失败");
        assertThat(cleanupFailed.failureMessage()).doesNotContain("supplier", "exception");
        assertThat(cleanupFailed.cleanedAt()).isNull();
        lifecycle.markCleaned("rebuild", rebuild.revision().id());
        assertThat(revisions.findById(rebuild.revision().id()).orElseThrow().cleanedAt()).isNotNull();
    }

    @Test
    void cleanupFailureWithoutAnEarlierFailureUsesFixedStageAndCode() {
        documents.insert(document("doc", "old", DocumentIndexStatus.INDEXED));
        revisions.insert(revision("old", "doc", 1, RevisionStatus.READY, 0, NOW, NOW));
        KnowledgeBuildContext context = lifecycle.begin("doc");
        chunks.insert(chunk("new-retrieval", context.revision().id(), null, ChunkRole.RETRIEVAL, 0));
        assertThat(revisions.updateChunkCount(context.revision().id(), 1)).isEqualTo(1);
        lifecycle.publish(context, HASH, 1, 1);

        lifecycle.recordCleanupFailure("doc", "old");

        var old = revisions.findById("old").orElseThrow();
        assertThat(old.revisionStatus()).isEqualTo(RevisionStatus.SUPERSEDED);
        assertThat(old.failureStage()).isEqualTo("CLEANUP");
        assertThat(old.failureCode()).isEqualTo("VECTOR_CLEANUP_FAILED");
        assertThat(old.failureMessage()).isEqualTo("向量清理失败");
    }

    @Test
    void cleanupFailureCannotTouchActiveRevision() {
        documents.insert(document("doc", "active", DocumentIndexStatus.INDEXED));
        revisions.insert(revision("active", "doc", 1, RevisionStatus.READY, 0, NOW, NOW));
        KnowledgeBuildContext context = lifecycle.begin("doc");
        lifecycle.fail(context, "CHUNK", "CHUNK_FAILED", "内容分块失败");

        assertThatThrownBy(() -> lifecycle.recordCleanupFailure("doc", "active"))
                .isInstanceOf(KnowledgeConflictException.class);
        lifecycle.recordCleanupFailure("doc", context.revision().id());
        var failed = revisions.findById(context.revision().id()).orElseThrow();
        assertThat(failed.failureStage()).isEqualTo("CHUNK");
        assertThat(failed.failureCode()).isEqualTo("CHUNK_FAILED");
        assertThat(failed.failureMessage()).isEqualTo("内容分块失败；向量清理失败");
    }

    private static KnowledgeDocument document(
            String id, String activeRevisionId, DocumentIndexStatus status) {
        return new KnowledgeDocument(
                id, "base", "file-" + id, 1, status, activeRevisionId, null, null, NOW, NOW);
    }

    private static KnowledgeDocumentRevision revision(
            String id,
            String documentId,
            int number,
            RevisionStatus status,
            int chunkCount,
            Instant completedAt,
            Instant publishedAt) {
        return new KnowledgeDocumentRevision(
                id,
                documentId,
                number,
                status,
                null,
                chunkCount,
                null,
                null,
                null,
                NOW,
                NOW,
                completedAt,
                publishedAt,
                null);
    }

    private static KnowledgeChunk chunk(
            String id, String revisionId, String parentId, ChunkRole role, int sequence) {
        return new KnowledgeChunk(
                id,
                revisionId,
                parentId,
                role,
                sequence,
                "正文 " + id,
                null,
                null,
                null,
                null,
                null,
                null,
                NOW);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LifecycleTestConfiguration {
        @Bean
        KnowledgeBuildLifecycleService knowledgeBuildLifecycleService(
                KnowledgeDocumentRepository documents,
                KnowledgeRevisionRepository revisions,
                KnowledgeChunkRepository chunks) {
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            return new KnowledgeBuildLifecycleService(documents, revisions, chunks, clock);
        }
    }
}
