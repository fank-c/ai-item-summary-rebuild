package com.item.aisummary.application.knowledge.service;

import com.item.aisummary.application.file.port.FileRepository;
import com.item.aisummary.application.knowledge.command.SaveKnowledgeChunkCommand;
import com.item.aisummary.application.knowledge.port.KnowledgeChunkRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.domain.file.model.FileAsset;
import com.item.aisummary.domain.knowledge.enums.RevisionStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.exception.KnowledgeNotFoundException;
import com.item.aisummary.domain.knowledge.model.KnowledgeChunk;
import com.item.aisummary.domain.knowledge.value.Heading;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 调用实际 Spring 服务验证批次原子性；失败回滚测试无测试外层事务，另验证事务组合。 */
@SpringBootTest
class KnowledgeChunkServiceTest {
    @Autowired KnowledgeBaseService bases;
    @Autowired KnowledgeDocumentService documents;
    @Autowired KnowledgeRevisionService revisions;
    @Autowired KnowledgeChunkService chunks;
    @Autowired KnowledgeChunkRepository chunkRepository;
    @MockitoSpyBean KnowledgeRevisionRepository revisionRepository;
    @Autowired FileRepository files;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    private String revisionId;

    @BeforeEach void resetAndCreateBuildingVersion() {
        jdbc.update("DELETE FROM knowledge_chunk");
        jdbc.update("DELETE FROM knowledge_document_revision");
        jdbc.update("DELETE FROM knowledge_document");
        jdbc.update("DELETE FROM knowledge_base");
        jdbc.update("DELETE FROM file_asset");
        var now = Instant.parse("2026-01-02T03:04:05.123456Z");
        files.insert(new FileAsset("file", "fixture.txt", "text/plain", 3, "minio", "files/fixture.txt", null,
            1, null, "completed", "owner", "owner", now, now));
        var document = documents.register("owner", bases.create("资料").id(), "file");
        revisionId = revisions.create(document.id()).id();
    }

    @Test void appendsBatchesSortsQueriesAndCountsAllSavedChunks() {
        var headings = List.of(new Heading(1, "总览"), new Heading(2, "细节"));
        var saved = chunks.saveBatch(revisionId, List.of(
            new SaveKnowledgeChunkCommand(2, "  第二部分\n", headings, 9, 1000, 1200, 2, 3), command(0)));
        assertThat(saved).hasSize(2).allSatisfy(chunk -> {
            assertThat(chunk.id()).matches("[0-9a-f]{32}");
            assertThat(chunk.revisionId()).isEqualTo(revisionId);
            assertThat(chunk.createAt().getNano() % 1000).isZero();
        });
        assertThat(chunks.findById(saved.getFirst().id())).isEqualTo(saved.getFirst());
        assertThat(saved.getFirst().content()).isEqualTo("  第二部分\n");
        assertThat(saved.getFirst().headingPath()).containsExactly(new Heading(1, "总览"), new Heading(2, "细节"));
        assertThat(revisions.findById(revisionId).chunkCount()).isEqualTo(2);
        chunks.saveBatch(revisionId, List.of(command(1)));
        assertThat(chunks.findByRevisionId(revisionId)).extracting(KnowledgeChunk::sequenceNo).containsExactly(0, 1, 2);
        var stored = revisions.findById(revisionId);
        assertThat(stored.chunkCount()).isEqualTo(3);
        assertThat(stored.revisionStatus()).isEqualTo(RevisionStatus.BUILDING);
        assertThat(stored.sourceSha256()).isNull();
        assertThat(stored.completedAt()).isNull();
        assertThat(stored.publishedAt()).isNull();
    }

    @Test void sameSequenceIsAllowedInDifferentVersionsAndReadsRemainScoped() {
        var first = chunks.saveBatch(revisionId, List.of(command(0))).getFirst();
        var other = revisions.create(revisions.findById(revisionId).knowledgeDocumentId());
        var second = chunks.saveBatch(other.id(), List.of(command(0))).getFirst();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(chunks.findByRevisionId(revisionId)).containsExactly(first);
        assertThat(chunks.findByRevisionId(other.id())).containsExactly(second);
    }

    @Test void lateUniqueConflictRollsBackEarlierInsertAndKeepsPreviousCount() {
        var original = chunks.saveBatch(revisionId, List.of(command(0))).getFirst();
        assertThatThrownBy(() -> chunks.saveBatch(revisionId, List.of(command(1), command(0))))
            .isInstanceOf(KnowledgeConflictException.class).extracting("code").isEqualTo("CHUNK_SEQUENCE_CONFLICT");
        assertThat(chunks.findByRevisionId(revisionId)).containsExactly(original);
        assertThat(revisions.findById(revisionId).chunkCount()).isEqualTo(1);
    }

    @Test void arbitraryLateDatabaseFailureRollsBackTheWholeBatch() {
        jdbc.execute("ALTER TABLE knowledge_chunk ADD CONSTRAINT fixture_sequence_guard CHECK (sequence_no <> 2)");
        try {
            assertThatThrownBy(() -> chunks.saveBatch(revisionId, List.of(command(1), command(2))))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(chunks.findByRevisionId(revisionId)).isEmpty();
            assertThat(revisions.findById(revisionId).chunkCount()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE knowledge_chunk DROP CONSTRAINT fixture_sequence_guard");
        }
    }

    @Test void countUpdateFailureRollsBackAllInsertedChunks() {
        jdbc.execute("ALTER TABLE knowledge_document_revision ADD CONSTRAINT fixture_count_guard CHECK (chunk_count = 0)");
        try {
            assertThatThrownBy(() -> chunks.saveBatch(revisionId, List.of(command(1), command(2))))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(chunks.findByRevisionId(revisionId)).isEmpty();
            assertThat(revisions.findById(revisionId).chunkCount()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE knowledge_document_revision DROP CONSTRAINT fixture_count_guard");
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 2})
    void unexpectedCountUpdateRowsRollBackBothChunksAndChangedCount(int affectedRows) {
        KnowledgeRevisionRepository target = AopTestUtils.getUltimateTargetObject(revisionRepository);
        doAnswer(invocation -> { invocation.callRealMethod(); return affectedRows; })
            .when(target).updateChunkCount(anyString(), anyInt());
        assertThatIllegalStateException().isThrownBy(() -> chunks.saveBatch(revisionId, List.of(command(0))));
        assertThat(chunks.findByRevisionId(revisionId)).isEmpty();
        assertThat(revisions.findById(revisionId).chunkCount()).isZero();
    }

    @Test void validatesTheEntireBatchBeforeAnyInsert() {
        for (var bad : List.of(
                new SaveKnowledgeChunkCommand(1, " \n", null, null, null, null, null, null),
                new SaveKnowledgeChunkCommand(-1, "正文", null, null, null, null, null, null),
                new SaveKnowledgeChunkCommand(1, "正文", null, -1, null, null, null, null),
                new SaveKnowledgeChunkCommand(1, "正文", null, null, 0, null, null, null),
                new SaveKnowledgeChunkCommand(1, "正文", null, null, 3, 2, null, null),
                new SaveKnowledgeChunkCommand(1, "正文", null, null, null, null, 0, 2),
                new SaveKnowledgeChunkCommand(1, "正文", null, null, null, null, 1, null))) {
            assertThatIllegalArgumentException().isThrownBy(() -> chunks.saveBatch(revisionId, List.of(command(5), bad)));
            assertThat(chunks.findByRevisionId(revisionId)).isEmpty();
            assertThat(revisions.findById(revisionId).chunkCount()).isZero();
        }
    }

    @Test void rejectsEmptyNullAndWithinBatchDuplicateInputs() {
        assertThatIllegalArgumentException().isThrownBy(() -> chunks.saveBatch(revisionId, null));
        assertThatIllegalArgumentException().isThrownBy(() -> chunks.saveBatch(revisionId, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> chunks.saveBatch(revisionId, List.of(command(1), command(1))));
        var input = new ArrayList<SaveKnowledgeChunkCommand>(); input.add(command(1)); input.add(null);
        assertThatIllegalArgumentException().isThrownBy(() -> chunks.saveBatch(revisionId, input));
        assertThat(chunks.findByRevisionId(revisionId)).isEmpty();
        assertThat(revisions.findById(revisionId).chunkCount()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"ready", "failed", "superseded"})
    void rejectsWritingAnyNonBuildingVersion(String status) {
        jdbc.update("UPDATE knowledge_document_revision SET revision_status=? WHERE id=?", status, revisionId);
        assertThatThrownBy(() -> chunks.saveBatch(revisionId, List.of(command(0))))
            .isInstanceOf(KnowledgeConflictException.class).extracting("code").isEqualTo("REVISION_NOT_BUILDING");
        assertThat(chunkRepository.countByRevisionId(revisionId)).isZero();
        assertThat(revisions.findById(revisionId).chunkCount()).isZero();
    }

    @Test void missingVersionAndMissingChunkReturnExplicitErrors() {
        assertThatExceptionOfType(KnowledgeNotFoundException.class).isThrownBy(() -> chunks.saveBatch("missing", List.of(command(0))));
        assertThatExceptionOfType(KnowledgeNotFoundException.class).isThrownBy(() -> chunks.findById("missing"));
        assertThatExceptionOfType(KnowledgeNotFoundException.class).isThrownBy(() -> chunks.findByRevisionId("missing"));
        assertThat(chunkRepository.countByRevisionId(revisionId)).isZero();
    }

    @Test void incompatibleOuterIsolationIsRejectedBeforeSaving() {
        var outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatIllegalStateException().isThrownBy(() -> outer.execute(status -> {
            chunks.saveBatch(revisionId, List.of(command(0)));
            return null;
        }));
        assertThat(chunks.findByRevisionId(revisionId)).isEmpty();
        assertThat(revisions.findById(revisionId).chunkCount()).isZero();
    }

    @Test void compatibleOuterRollbackAlsoRollsBackSuccessfulBatchAndCount() {
        var outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThatIllegalStateException().isThrownBy(() -> outer.execute(status -> {
            chunks.saveBatch(revisionId, List.of(command(0)));
            assertThat(revisions.findById(revisionId).chunkCount()).isEqualTo(1);
            throw new IllegalStateException("outer transaction aborted");
        })).withMessage("outer transaction aborted");
        assertThat(chunks.findByRevisionId(revisionId)).isEmpty();
        assertThat(revisions.findById(revisionId).chunkCount()).isZero();
    }

    @Test void concurrentBatchesKeepCountEqualToActualSavedRows() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return chunks.saveBatch(revisionId, List.of(command(0))); });
            var second = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return chunks.saveBatch(revisionId, List.of(command(1))); });
            assertThat(first.get(15, TimeUnit.SECONDS)).hasSize(1);
            assertThat(second.get(15, TimeUnit.SECONDS)).hasSize(1);
        }
        assertThat(chunks.findByRevisionId(revisionId)).extracting(KnowledgeChunk::sequenceNo).containsExactly(0, 1);
        assertThat(revisions.findById(revisionId).chunkCount()).isEqualTo(2);
    }

    private static SaveKnowledgeChunkCommand command(int sequence) {
        return new SaveKnowledgeChunkCommand(sequence, "正文 " + sequence, null, null, null, null, null, null);
    }
}
