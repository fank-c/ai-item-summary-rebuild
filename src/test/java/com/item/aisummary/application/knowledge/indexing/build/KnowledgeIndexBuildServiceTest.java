package com.item.aisummary.application.knowledge.indexing.build;

import com.item.aisummary.application.auth.AuthenticationRequiredException;
import com.item.aisummary.application.file.content.FileContentResource;
import com.item.aisummary.application.file.content.FileContentService;
import com.item.aisummary.application.file.content.ProcessableFileAsset;
import com.item.aisummary.application.file.port.FileRepository;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkDraft;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkGroupDraft;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingMode;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingOptions;
import com.item.aisummary.application.knowledge.indexing.chunk.KnowledgeChunkGroupWriter;
import com.item.aisummary.application.knowledge.indexing.chunk.KnowledgeChunker;
import com.item.aisummary.application.knowledge.indexing.parse.ContentBlock;
import com.item.aisummary.application.knowledge.indexing.parse.ContentBlockType;
import com.item.aisummary.application.knowledge.indexing.parse.KnowledgeDocumentParser;
import com.item.aisummary.application.knowledge.indexing.parse.ParsedDocument;
import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.application.knowledge.indexing.source.KnowledgeSourceReader;
import com.item.aisummary.application.knowledge.indexing.source.PreparedSource;
import com.item.aisummary.application.knowledge.indexing.vector.KnowledgeVectorEntry;
import com.item.aisummary.application.knowledge.indexing.vector.KnowledgeVectorIndex;
import com.item.aisummary.application.knowledge.port.KnowledgeChunkRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.application.knowledge.service.KnowledgeBaseService;
import com.item.aisummary.application.knowledge.service.KnowledgeChunkService;
import com.item.aisummary.application.knowledge.service.KnowledgeDocumentService;
import com.item.aisummary.application.knowledge.service.KnowledgeRevisionService;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildException;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildStage;
import com.item.aisummary.domain.file.model.FileAsset;
import com.item.aisummary.domain.knowledge.enums.ChunkRole;
import com.item.aisummary.domain.knowledge.enums.DocumentIndexStatus;
import com.item.aisummary.domain.knowledge.enums.RevisionStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库构建核心编排服务（KnowledgeIndexBuildService）端到端集成测试。
 *
 * <p>使用测试策略替身验证全量构建流程的各个边界：
 * <ul>
 *   <li>大组独立保存与跨组凑批、仅 RETRIEVAL 写入向量库及末尾 flush 尾批。</li>
 *   <li>源文件读取前校验、摘要不匹配拦截与流资源关闭。</li>
 *   <li>解析/切分策略缺失拦截、FLAT 模式禁止父块校验。</li>
 *   <li>向量部分写入失败触发当前版本清理并保留旧活动版本。</li>
 *   <li>发布事务 SQL 失败时版本指针不切换、派生向量保留待核对。</li>
 *   <li>Propagation.NEVER 拒绝外层事务，以及文档 BUILDING 互斥锁定。</li>
 * </ul>
 */
@SpringBootTest
@Import(KnowledgeIndexBuildServiceTest.Configuration.class)
class KnowledgeIndexBuildServiceTest {
    private static final String HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("testBuild") KnowledgeIndexBuildService builds;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("testParentBuild") KnowledgeIndexBuildService parentBuilds;
    @Autowired KnowledgeBaseService bases;
    @Autowired KnowledgeDocumentService documents;
    @Autowired KnowledgeRevisionService revisions;
    @Autowired KnowledgeChunkService chunks;
    @Autowired KnowledgeBuildLifecycleService lifecycle;
    @Autowired FileRepository files;
    @Autowired Fixture fixture;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private String documentId;

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM knowledge_chunk");
        jdbc.update("DELETE FROM knowledge_document_revision");
        jdbc.update("DELETE FROM knowledge_document");
        jdbc.update("DELETE FROM knowledge_base");
        jdbc.update("DELETE FROM file_asset");
        fixture.reset();
        Instant now = Instant.parse("2026-01-02T03:04:05.123456Z");
        files.insert(new FileAsset("file", "fixture.txt", "text/plain", 3, "minio", "files/fixture.txt", HASH,
                1, null, "completed", "owner", "owner", now, now));
        documentId = documents.register("owner", bases.create("资料").id(), "file").id();
    }

    @Test void savesWholeLargeGroupButSplitsRetrievalVectorsAndFlushesTail() {
        fixture.groups = List.of(new ChunkGroupDraft(draft("父块"), List.of(draft("一"), draft("二"), draft("三"))),
                new ChunkGroupDraft(null, List.of(draft("四"), draft("五"))));
        var result = parentBuilds.build("owner", documentId, ChunkingMode.PARENT_CHILD);
        assertThat(result.chunkCount()).isEqualTo(6);
        assertThat(result.retrievalChunkCount()).isEqualTo(5);
        assertThat(fixture.batches).extracting(List::size).containsExactly(2, 2, 1);
        assertThat(fixture.batches.stream().flatMap(List::stream).toList()).allSatisfy(entry -> {
            assertThat(entry.chunk().chunkRole()).isEqualTo(ChunkRole.RETRIEVAL);
            assertThat(chunks.findById(entry.chunk().id())).isEqualTo(entry.chunk());
            assertThat(entry.sourceSha256()).isEqualTo(HASH);
        });
        assertThat(chunks.findByRevisionId(result.revisionId())).extracting("sequenceNo").containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(documents.findById(documentId).activeRevisionId()).isEqualTo(result.revisionId());
        assertThat(revisions.findById(result.revisionId()).revisionStatus()).isEqualTo(RevisionStatus.READY);
        assertThat(fixture.closed).isTrue();
        assertThat(fixture.externalInsideTransaction).isFalse();
    }

    @Test void verifiesSourcesBeforeParsingAndClosesResourceOnMismatch() {
        fixture.hash = "0".repeat(64);
        assertThatThrownBy(() -> builds.build("owner", documentId)).isInstanceOf(KnowledgeBuildException.class)
                .extracting("stage", "code", "cleanupCompleted")
                .containsExactly(KnowledgeBuildStage.SOURCE_READ, "SOURCE_DIGEST_MISMATCH", true);
        assertThat(fixture.parseCalls).isZero();
        assertThat(fixture.closed).isTrue();
        assertThat(documents.findById(documentId).indexStatus()).isEqualTo(DocumentIndexStatus.FAILED);
        assertThat(revisions.findByDocumentId(documentId).getFirst().cleanedAt()).isNotNull();
    }

    @Test void missingStrategyAndInvalidActorAreRejectedBeforeCreatingRevisionOrOpeningStream() {
        when(fixture.files.requireOwnedProcessable("owner", "file"))
                .thenReturn(new ProcessableFileAsset("fixture.pdf", "application/pdf"));
        assertThatIllegalStateException().isThrownBy(() -> builds.build("owner", documentId));
        assertThatIllegalStateException().isThrownBy(() -> builds.build("owner", documentId, ChunkingMode.PARENT_CHILD));
        assertThatExceptionOfType(AuthenticationRequiredException.class).isThrownBy(() -> builds.build(null, documentId));
        assertThatIllegalArgumentException().isThrownBy(() -> builds.build("x".repeat(65), documentId));
        assertThat(revisions.findByDocumentId(documentId)).isEmpty();
        verify(fixture.files, never()).openForProcessing(anyString(), anyString());
    }

    @Test void parserAndChunkerFailuresAreSafeAndDoNotPublish() {
        fixture.parseFailure = true;
        assertThatThrownBy(() -> builds.build("owner", documentId)).isInstanceOf(KnowledgeBuildException.class)
                .hasMessageNotContaining("private").extracting("stage").isEqualTo(KnowledgeBuildStage.PARSE);
        assertThat(fixture.closed).isTrue();
        fixture.parseFailure = false;
        fixture.groups = List.of();
        assertThatThrownBy(() -> builds.build("owner", documentId)).isInstanceOf(KnowledgeBuildException.class)
                .extracting("stage").isEqualTo(KnowledgeBuildStage.CHUNK);
        assertThat(documents.findById(documentId).activeRevisionId()).isNull();
    }

    @Test void maxChunksCountsBothRolesAndRejectsBeforeSavingAnyGroup() {
        fixture.groups = List.of(new ChunkGroupDraft(null, java.util.Collections.nCopies(21, draft("子"))));
        assertThatThrownBy(() -> builds.build("owner", documentId)).isInstanceOf(KnowledgeBuildException.class)
                .extracting("stage").isEqualTo(KnowledgeBuildStage.CHUNK);
        assertThat(chunks.findByRevisionId(revisions.findByDocumentId(documentId).getFirst().id())).isEmpty();
        assertThat(fixture.batches).isEmpty();
    }

    @Test void vectorPartialFailureCleansOnlyNewRevisionAndKeepsOldActiveVersion() {
        var original = builds.build("owner", documentId);
        fixture.writeFailure = true;
        assertThatThrownBy(() -> builds.build("owner", documentId)).isInstanceOf(KnowledgeBuildException.class)
                .extracting("stage", "cleanupCompleted").containsExactly(KnowledgeBuildStage.VECTOR_WRITE, true);
        var failed = revisions.findByDocumentId(documentId).getLast();
        assertThat(failed.revisionStatus()).isEqualTo(RevisionStatus.FAILED);
        assertThat(failed.cleanedAt()).isNotNull();
        assertThat(fixture.deleted).containsExactly(failed.id());
        assertThat(documents.findById(documentId).activeRevisionId()).isEqualTo(original.revisionId());
        assertThat(documents.findById(documentId).indexStatus()).isEqualTo(DocumentIndexStatus.INDEXED);
        assertThat(chunks.findByRevisionId(failed.id())).isNotEmpty();
    }

    @Test void cleanupFailurePreservesOriginalStageAndReportsIndependentFact() {
        fixture.writeFailure = true;
        fixture.deleteFailure = true;
        assertThatThrownBy(() -> builds.build("owner", documentId)).isInstanceOf(KnowledgeBuildException.class)
                .extracting("stage", "cleanupCompleted").containsExactly(KnowledgeBuildStage.VECTOR_WRITE, false);
        var failed = revisions.findByDocumentId(documentId).getFirst();
        assertThat(failed.cleanedAt()).isNull();
        assertThat(failed.failureStage()).isEqualTo("VECTOR_WRITE");
        assertThat(failed.failureMessage()).contains("向量清理失败").doesNotContain("private");
    }

    @Test void successfulDeleteButFailedCleanedAtWriteDoesNotInventVectorFailure() {
        fixture.writeFailure = true;
        jdbc.execute("ALTER TABLE knowledge_document_revision ADD CONSTRAINT fixture_cleaned_guard CHECK (cleaned_at IS NULL)");
        try {
            assertThatThrownBy(() -> builds.build("owner", documentId)).isInstanceOf(KnowledgeBuildException.class)
                    .extracting("stage", "cleanupCompleted").containsExactly(KnowledgeBuildStage.VECTOR_WRITE, false);
            var failed = revisions.findByDocumentId(documentId).getFirst();
            assertThat(fixture.deleted).containsExactly(failed.id());
            assertThat(failed.cleanedAt()).isNull();
            assertThat(failed.failureMessage()).isEqualTo("检索片段向量写入失败");
        } finally { jdbc.execute("ALTER TABLE knowledge_document_revision DROP CONSTRAINT fixture_cleaned_guard"); }
    }

    @Test void oldCleanupFailureDoesNotUndoPublishedVersion() {
        var original = builds.build("owner", documentId);
        fixture.deleteFailure = true;
        var rebuilt = builds.build("owner", documentId);
        assertThat(rebuilt.oldRevisionCleanupCompleted()).isFalse();
        assertThat(documents.findById(documentId).activeRevisionId()).isEqualTo(rebuilt.revisionId());
        assertThat(revisions.findById(original.revisionId()).revisionStatus()).isEqualTo(RevisionStatus.SUPERSEDED);
        assertThat(revisions.findById(original.revisionId()).failureCode()).isEqualTo("VECTOR_CLEANUP_FAILED");
    }

    @Test void publishSqlFailureRollsBackPointerAndRetainsVectorsForReconciliation() {
        jdbc.execute("ALTER TABLE knowledge_document ADD CONSTRAINT fixture_publish_guard CHECK (active_revision_id IS NULL)");
        try {
            assertThatThrownBy(() -> builds.build("owner", documentId)).isInstanceOf(KnowledgeBuildException.class)
                    .extracting("stage", "cleanupCompleted").containsExactly(KnowledgeBuildStage.PUBLISH, false);
            assertThat(documents.findById(documentId).activeRevisionId()).isNull();
            assertThat(fixture.deleted).isEmpty();
            assertThat(fixture.batches).isNotEmpty();
        } finally { jdbc.execute("ALTER TABLE knowledge_document DROP CONSTRAINT fixture_publish_guard"); }
    }

    @Test void proxyRejectsOuterTransactionAndExistingBuilding() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> builds.build("owner", documentId)));
        assertThat(revisions.findByDocumentId(documentId)).isEmpty();
        lifecycle.begin(documentId);
        assertThatThrownBy(() -> builds.build("owner", documentId)).isInstanceOf(KnowledgeConflictException.class)
                .extracting("code").isEqualTo("BUILD_ALREADY_RUNNING");
        verify(fixture.files, never()).openForProcessing(anyString(), anyString());
    }

    @Test void rejectsParentOutputFromFlatModeAndDatabaseFailuresDoNotPublish() {
        fixture.groups = List.of(new ChunkGroupDraft(draft("父"), List.of(draft("子"))));
        assertThatThrownBy(() -> builds.build("owner", documentId)).isInstanceOf(KnowledgeBuildException.class)
                .extracting("stage").isEqualTo(KnowledgeBuildStage.CHUNK);
        fixture.groups = List.of(new ChunkGroupDraft(null, List.of(draft("第一"), draft("第二"))));
        jdbc.execute("ALTER TABLE knowledge_chunk ADD CONSTRAINT fixture_build_guard CHECK (sequence_no <> 1)");
        try {
            assertThatThrownBy(() -> builds.build("owner", documentId)).isInstanceOf(KnowledgeBuildException.class)
                    .extracting("stage").isEqualTo(KnowledgeBuildStage.CHUNK_SAVE);
            var failed = revisions.findByDocumentId(documentId).getLast();
            assertThat(chunks.findByRevisionId(failed.id())).isEmpty();
            assertThat(fixture.batches).isEmpty();
        } finally { jdbc.execute("ALTER TABLE knowledge_chunk DROP CONSTRAINT fixture_build_guard"); }
    }

    private static ChunkDraft draft(String content) { return new ChunkDraft(content, null, null, null); }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean Fixture fixture() { return new Fixture(); }
        @Bean KnowledgeBuildLifecycleService testLifecycle(KnowledgeDocumentRepository documents,
                KnowledgeRevisionRepository revisions, KnowledgeChunkRepository chunks, Clock clock) {
            return new KnowledgeBuildLifecycleService(documents, revisions, chunks, clock);
        }
        @Bean KnowledgeChunkGroupWriter knowledgeChunkGroupWriter(KnowledgeChunkService chunks) {
            return new KnowledgeChunkGroupWriter(chunks);
        }
        @Bean KnowledgeIndexBuildService testBuild(Fixture f, KnowledgeDocumentRepository documents,
                KnowledgeChunkGroupWriter chunks, KnowledgeBuildLifecycleService lifecycle) {
            var router = new KnowledgeProcessingRouter(List.of(f), List.of(f));
            return new KnowledgeIndexBuildService(f.files, documents, router, new KnowledgeSourceReader(20),
                    chunks, f, lifecycle, new ChunkingOptions(512, 200, 0, 20, true, ".?!\n"), 2);
        }
        @Bean KnowledgeIndexBuildService testParentBuild(Fixture f, KnowledgeDocumentRepository documents,
                KnowledgeChunkGroupWriter chunks, KnowledgeBuildLifecycleService lifecycle) {
            var parent = new KnowledgeChunker() {
                @Override public ChunkingMode mode() { return ChunkingMode.PARENT_CHILD; }
                @Override public List<ChunkGroupDraft> chunk(ParsedDocument document, ChunkingOptions options) {
                    return f.chunk(document, options);
                }
            };
            return new KnowledgeIndexBuildService(f.files, documents,
                    new KnowledgeProcessingRouter(List.of(f), List.of(f, parent)), new KnowledgeSourceReader(20),
                    chunks, f, lifecycle, new ChunkingOptions(512, 200, 0, 20, true, ".?!\n"), 2);
        }
    }

    static class Fixture implements KnowledgeDocumentParser, KnowledgeChunker, KnowledgeVectorIndex {
        final FileContentService files = mock(FileContentService.class);
        List<ChunkGroupDraft> groups;
        final List<List<KnowledgeVectorEntry>> batches = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        boolean closed, parseFailure, writeFailure, deleteFailure, externalInsideTransaction;
        int parseCalls;
        String hash;
        void reset() {
            org.mockito.Mockito.reset(files);
            groups = List.of(new ChunkGroupDraft(null, List.of(draft("片段"))));
            batches.clear(); deleted.clear(); hash = HASH; parseCalls = 0;
            closed = parseFailure = writeFailure = deleteFailure = externalInsideTransaction = false;
            when(files.requireOwnedProcessable("owner", "file")).thenReturn(new ProcessableFileAsset("fixture.txt", "text/plain"));
            when(files.openForProcessing("owner", "file")).thenAnswer(invocation -> new FileContentResource(
                    "file", "fixture.txt", "text/plain", 3, hash, new ByteArrayInputStream(new byte[]{97, 98, 99}) {
                        @Override public void close() { closed = true; }
                    }));
        }
        @Override public FileFormat format() { return FileFormat.TXT; }
        @Override public ChunkingMode mode() { return ChunkingMode.FLAT; }
        @Override public ParsedDocument parse(PreparedSource source) {
            observeTransaction(); parseCalls++;
            if (parseFailure) throw new IllegalStateException("private parse body");
            return new ParsedDocument(List.of(new ContentBlock(0, ContentBlockType.TEXT, "解析替身", null, null, false)));
        }
        @Override public List<ChunkGroupDraft> chunk(ParsedDocument document, ChunkingOptions options) {
            observeTransaction(); return groups;
        }
        @Override public void add(List<KnowledgeVectorEntry> entries) {
            observeTransaction(); batches.add(List.copyOf(entries));
            if (writeFailure) throw new IllegalStateException("private vector body");
        }
        @Override public void deleteByRevisionId(String revisionId) {
            observeTransaction(); deleted.add(revisionId);
            if (deleteFailure) throw new IllegalStateException("private cleanup body");
        }
        private void observeTransaction() {
            externalInsideTransaction |= org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        }
    }
}
