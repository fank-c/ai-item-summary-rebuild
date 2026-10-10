package com.item.aisummary.application.knowledge.service;

import com.item.aisummary.application.auth.AuthenticationRequiredException;
import com.item.aisummary.application.file.port.FileRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeBaseRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.domain.file.exception.FileNotFoundException;
import com.item.aisummary.domain.file.model.FileAsset;
import com.item.aisummary.domain.knowledge.enums.DocumentIndexStatus;
import com.item.aisummary.domain.knowledge.enums.RevisionStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.exception.KnowledgeNotFoundException;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocument;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocumentRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 H2 与文件元数据服务验证内部用例；两个对象存储均关闭，禁止读正文。 */
@SpringBootTest
@Import(KnowledgeServicesTest.FixedTime.class)
class KnowledgeServicesTest {
    @Autowired KnowledgeBaseService bases;
    @Autowired KnowledgeDocumentService documents;
    @Autowired KnowledgeRevisionService revisions;
    @Autowired KnowledgeBaseRepository baseRepository;
    @Autowired KnowledgeDocumentRepository documentRepository;
    @Autowired KnowledgeRevisionRepository revisionRepository;
    @Autowired FileRepository files;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05.123456Z");

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM knowledge_chunk");
        jdbc.update("DELETE FROM knowledge_document_revision");
        jdbc.update("DELETE FROM knowledge_document");
        jdbc.update("DELETE FROM knowledge_base");
        jdbc.update("DELETE FROM file_asset");
    }

    @Test void createsMultipleSameNamedBasesWithIndependentUuidIdsAndUtcMicros() {
        var first = bases.create("  学习资料  ");
        var second = bases.create("学习资料");
        assertThat(first.id()).matches("[0-9a-f]{32}").isNotEqualTo(second.id());
        assertThat(first.name()).isEqualTo("学习资料");
        assertThat(first.status()).isEqualTo(1);
        assertThat(first.createAt()).isEqualTo(NOW);
        assertThat(first.updateAt()).isEqualTo(NOW);
        assertThat(bases.findById(first.id())).isEqualTo(first);
        assertThat(bases.findAll()).containsExactlyInAnyOrder(first, second);
    }

    @Test void renamesOnlyTheNameAndUpdateTime() {
        var base = bases.create("原名称");
        var originalCreated = NOW.minusSeconds(60);
        jdbc.update("UPDATE knowledge_base SET status=7, create_at=? WHERE id=?",
            java.time.LocalDateTime.ofInstant(originalCreated, ZoneOffset.UTC), base.id());
        var renamed = bases.rename(base.id(), " 新名称 ");
        assertThat(renamed.name()).isEqualTo("新名称");
        assertThat(renamed.status()).isEqualTo(7);
        assertThat(renamed.createAt()).isEqualTo(originalCreated);
        assertThat(renamed.updateAt()).isEqualTo(NOW);
        assertThat(bases.findById(base.id())).isEqualTo(renamed);
    }

    @Test void rejectsInvalidNamesWithoutCreatingOrChangingRows() {
        var base = bases.create("资料");
        for (String name : new String[] {null, " \n", "文".repeat(101)}) {
            assertThatIllegalArgumentException().isThrownBy(() -> bases.create(name));
            assertThatIllegalArgumentException().isThrownBy(() -> bases.rename(base.id(), name));
        }
        assertThat(bases.findAll()).containsExactly(base);
    }

    @Test void registersPendingDocumentsUsingOnlyOwnedFileMetadata() {
        var base = bases.create("资料");
        addFile("file", "owner", 1, null, "completed");
        var document = documents.register("owner", base.id(), "file");
        assertThat(document.id()).matches("[0-9a-f]{32}");
        assertThat(document.knowledgeBaseId()).isEqualTo(base.id());
        assertThat(document.fileId()).isEqualTo("file");
        assertThat(document.indexStatus()).isEqualTo(DocumentIndexStatus.PENDING);
        assertThat(document.status()).isEqualTo(1);
        assertThat(document.activeRevisionId()).isNull();
        assertThat(document.lastFailureCode()).isNull();
        assertThat(document.lastFailureMessage()).isNull();
        assertThat(document.createAt()).isEqualTo(NOW);
        assertThat(documents.findById(document.id())).isEqualTo(document);
        assertThat(documents.findByKnowledgeBaseId(base.id())).containsExactly(document);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_revision", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Integer.class)).isZero();
    }

    @Test void duplicateRegistrationConflictsButSameFileInAnotherBaseIsAllowed() {
        var first = bases.create("一");
        var second = bases.create("二");
        addFile("file", "owner", 1, null, "completed");
        var document = documents.register("owner", first.id(), "file");
        assertThatThrownBy(() -> documents.register("owner", first.id(), "file"))
            .isInstanceOf(KnowledgeConflictException.class).extracting("code").isEqualTo("DOCUMENT_ALREADY_REGISTERED");
        var another = documents.register("owner", second.id(), "file");
        assertThat(another.id()).isNotEqualTo(document.id());
        assertThat(documents.findByKnowledgeBaseId(first.id())).containsExactly(document);
    }

    @ParameterizedTest @ValueSource(strings = {"other-owner", "disabled", "deleted", "pending", "missing"})
    void rejectsUnavailableAndForeignFilesWithoutRegistering(String scenario) {
        var base = bases.create("资料");
        if (!scenario.equals("missing")) addFile("file", scenario.equals("other-owner") ? "other" : "owner",
            scenario.equals("disabled") ? 0 : 1, scenario.equals("deleted") ? NOW : null,
            scenario.equals("pending") ? "pending" : "completed");
        assertThatExceptionOfType(FileNotFoundException.class)
            .isThrownBy(() -> documents.register("owner", base.id(), "file"));
        assertThat(documents.findByKnowledgeBaseId(base.id())).isEmpty();
    }

    @Test void explicitIdentityIsRequiredAndNotTrimmed() {
        var base = bases.create("资料");
        addFile("file", " owner ", 1, null, "completed");
        for (String actor : new String[] {null, "", " "}) {
            assertThatExceptionOfType(AuthenticationRequiredException.class)
                .isThrownBy(() -> documents.register(actor, base.id(), "file"));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> documents.register("a".repeat(65), base.id(), "file"));
        assertThatExceptionOfType(FileNotFoundException.class).isThrownBy(() -> documents.register("owner", base.id(), "file"));
        assertThat(documents.register(" owner ", base.id(), "file").fileId()).isEqualTo("file");
    }

    @Test void rejectsMissingAssociationsAndDetailsInsteadOfReturningEmptyRelations() {
        assertThatExceptionOfType(KnowledgeNotFoundException.class).isThrownBy(() -> bases.findById("missing"));
        assertThatExceptionOfType(KnowledgeNotFoundException.class).isThrownBy(() -> bases.rename("missing", "名称"));
        assertThatExceptionOfType(KnowledgeNotFoundException.class).isThrownBy(() -> documents.register("owner", "missing", "file"));
        assertThatExceptionOfType(KnowledgeNotFoundException.class).isThrownBy(() -> documents.findById("missing"));
        assertThatExceptionOfType(KnowledgeNotFoundException.class).isThrownBy(() -> documents.findByKnowledgeBaseId("missing"));
        assertThatExceptionOfType(KnowledgeNotFoundException.class).isThrownBy(() -> revisions.create("missing"));
        assertThatExceptionOfType(KnowledgeNotFoundException.class).isThrownBy(() -> revisions.findById("missing"));
        assertThatExceptionOfType(KnowledgeNotFoundException.class).isThrownBy(() -> revisions.findByDocumentId("missing"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document", Integer.class)).isZero();
    }

    @Test void createsIncrementingBuildingVersionsWithoutFakeResultsOrActivePointerChanges() {
        var base = bases.create("资料");
        addFile("file", "owner", 1, null, "completed");
        var document = documents.register("owner", base.id(), "file");
        jdbc.update("UPDATE knowledge_document SET active_revision_id='old-active', index_status='indexed', last_failure_code='old-code' WHERE id=?", document.id());
        var first = revisions.create(document.id());
        var second = revisions.create(document.id());
        assertThat(first.id()).matches("[0-9a-f]{32}");
        assertThat(first.revisionNumber()).isEqualTo(1);
        assertThat(second.revisionNumber()).isEqualTo(2);
        assertThat(first.revisionStatus()).isEqualTo(RevisionStatus.BUILDING);
        assertThat(first.chunkCount()).isZero();
        assertThat(first.sourceSha256()).isNull();
        assertThat(first.failureStage()).isNull();
        assertThat(first.failureCode()).isNull();
        assertThat(first.failureMessage()).isNull();
        assertThat(first.createdAt()).isEqualTo(NOW);
        assertThat(first.startedAt()).isEqualTo(NOW);
        assertThat(first.completedAt()).isNull();
        assertThat(first.publishedAt()).isNull();
        assertThat(first.cleanedAt()).isNull();
        assertThat(revisions.findById(first.id())).isEqualTo(first);
        assertThat(revisions.findByDocumentId(document.id())).containsExactly(first, second);
        var stored = documents.findById(document.id());
        assertThat(stored.activeRevisionId()).isEqualTo("old-active");
        assertThat(stored.indexStatus()).isEqualTo(DocumentIndexStatus.INDEXED);
        assertThat(stored.lastFailureCode()).isEqualTo("old-code");
    }

    @Test void exhaustedVersionNumberFailsWithoutOverflow() {
        var document = createDocument();
        jdbc.update("INSERT INTO knowledge_document_revision (id,knowledge_document_id,revision_number,revision_status,chunk_count,created_at,started_at) VALUES ('max',?,2147483647,'building',0,?,?)",
            document.id(), java.time.LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), java.time.LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> revisions.create(document.id())).isInstanceOf(KnowledgeConflictException.class)
            .extracting("code").isEqualTo("REVISION_NUMBER_EXHAUSTED");
        assertThat(revisions.findByDocumentId(document.id())).hasSize(1);
    }

    @Test void concurrentMaxPlusOneRaceHasExactlyOneWinnerAndOneExplicitConflict() throws Exception {
        var document = createDocument();
        var barrier = new CyclicBarrier(2);
        KnowledgeRevisionRepository target = AopTestUtils.getUltimateTargetObject(revisionRepository);
        var racingRepository = spy(target);
        doAnswer(invocation -> {
            int max = revisionRepository.findMaxRevisionNumber(document.id());
            barrier.await(10, TimeUnit.SECONDS);
            return max;
        }).when(racingRepository).findMaxRevisionNumber(document.id());
        var racingService = new KnowledgeRevisionService(documentRepository, racingRepository, clock);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> createResult(racingService, document.id()));
            var b = executor.submit(() -> createResult(racingService, document.id()));
            var results = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(KnowledgeDocumentRevision.class::isInstance).count()).isEqualTo(1);
            var conflict = results.stream().filter(KnowledgeConflictException.class::isInstance).findFirst().orElseThrow();
            assertThat(((KnowledgeConflictException) conflict).code()).isEqualTo("REVISION_NUMBER_CONFLICT");
        }
        assertThat(revisions.findByDocumentId(document.id())).hasSize(1);
    }

    private static Object createResult(KnowledgeRevisionService service, String documentId) {
        try { return service.create(documentId); } catch (KnowledgeConflictException exception) { return exception; }
    }
    private KnowledgeDocument createDocument() {
        var base = bases.create("资料");
        addFile("file", "owner", 1, null, "completed");
        return documents.register("owner", base.id(), "file");
    }
    private void addFile(String id, String owner, int status, Instant deleted, String uploadStatus) {
        files.insert(new FileAsset(id, "fixture.txt", "text/plain", 3, "minio", "files/fixture.txt", null,
            status, deleted, uploadStatus, owner, owner, NOW, NOW));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean @Primary Clock knowledgeTestClock() {
            return Clock.fixed(Instant.parse("2026-01-02T03:04:05.123456789Z"), ZoneOffset.UTC);
        }
    }
}
