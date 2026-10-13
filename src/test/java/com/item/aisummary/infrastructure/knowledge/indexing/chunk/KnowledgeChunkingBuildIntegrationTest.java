package com.item.aisummary.infrastructure.knowledge.indexing.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.item.aisummary.application.file.content.FileContentResource;
import com.item.aisummary.application.file.content.FileContentService;
import com.item.aisummary.application.file.content.ProcessableFileAsset;
import com.item.aisummary.application.file.port.FileRepository;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildException;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildLifecycleService;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildStage;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeIndexBuildService;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeProcessingRouter;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingMode;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingOptions;
import com.item.aisummary.application.knowledge.indexing.chunk.KnowledgeChunkGroupWriter;
import com.item.aisummary.application.knowledge.indexing.source.KnowledgeSourceReader;
import com.item.aisummary.application.knowledge.indexing.vector.KnowledgeVectorEntry;
import com.item.aisummary.application.knowledge.indexing.vector.KnowledgeVectorIndex;
import com.item.aisummary.application.knowledge.port.KnowledgeChunkRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.application.knowledge.service.KnowledgeBaseService;
import com.item.aisummary.application.knowledge.service.KnowledgeChunkService;
import com.item.aisummary.application.knowledge.service.KnowledgeDocumentService;
import com.item.aisummary.application.knowledge.service.KnowledgeRevisionService;
import com.item.aisummary.domain.file.model.FileAsset;
import com.item.aisummary.domain.knowledge.enums.ChunkRole;
import com.item.aisummary.domain.knowledge.enums.DocumentIndexStatus;
import com.item.aisummary.domain.knowledge.enums.RevisionStatus;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.MarkdownKnowledgeDocumentParser;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.TextKnowledgeDocumentParser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实本地 parser/chunker、H2 Mapper 与发布；文件读取和向量后端仍是无网络替身。 */
@SpringBootTest
@Import(KnowledgeChunkingBuildIntegrationTest.Configuration.class)
class KnowledgeChunkingBuildIntegrationTest {
    @Autowired @Qualifier("chunkingBuild") KnowledgeIndexBuildService builds;
    @Autowired @Qualifier("limitedChunkingBuild") KnowledgeIndexBuildService limited;
    @Autowired KnowledgeBaseService bases;
    @Autowired KnowledgeDocumentService documents;
    @Autowired KnowledgeRevisionService revisions;
    @Autowired KnowledgeChunkService chunks;
    @Autowired FileRepository files;
    @Autowired Fixture fixture;
    @Autowired JdbcTemplate jdbc;
    private String documentId;

    @BeforeEach void prepare() throws Exception {
        jdbc.update("DELETE FROM knowledge_chunk");
        jdbc.update("DELETE FROM knowledge_document_revision");
        jdbc.update("DELETE FROM knowledge_document");
        jdbc.update("DELETE FROM knowledge_base");
        jdbc.update("DELETE FROM file_asset");
        fixture.configure("fixture.md", "# 上传说明\n\n" + "上传完成后核对大小并保存摘要，数据库失败需记录处理状态。".repeat(35)
                + "\n\n```java\ncall();\n```\n\n## 结束\n\n尾部内容");
        createDocument();
    }

    private void createDocument() {
        Instant now = Instant.parse("2026-01-02T03:04:05.123456Z");
        files.insert(new FileAsset("file", fixture.name, "text/plain", fixture.bytes.length, "minio", "files/fixture", fixture.hash,
                1, null, "completed", "owner", "owner", now, now));
        documentId = documents.register("owner", bases.create("资料").id(), "file").id();
    }

    @ParameterizedTest @EnumSource(ChunkingMode.class)
    void buildsFromRealMarkdownAndPersistsExactMetadataAndOnlyRetrievalVectors(ChunkingMode mode) {
        var result = builds.build("owner", documentId, mode);
        var saved = chunks.findByRevisionId(result.revisionId());
        assertThat(saved).hasSize(result.chunkCount());
        assertThat(revisions.findById(result.revisionId()).revisionStatus()).isEqualTo(RevisionStatus.READY);
        assertThat(documents.findById(documentId).activeRevisionId()).isEqualTo(result.revisionId());
        assertThat(documents.findById(documentId).indexStatus()).isEqualTo(DocumentIndexStatus.INDEXED);
        assertThat(fixture.closed).isTrue();
        assertThat(fixture.vectors).hasSize(result.retrievalChunkCount()).allSatisfy(entry -> {
            assertThat(entry.chunk().chunkRole()).isEqualTo(ChunkRole.RETRIEVAL);
            assertThat(chunks.findById(entry.chunk().id())).isEqualTo(entry.chunk());
        });
        assertThat(fixture.batchSizes).allMatch(size -> size <= 3);
        var tokens = new JTokkitTokenBudget();
        assertThat(saved).allSatisfy(chunk -> {
            assertThat(chunk.tokenCount()).isEqualTo(tokens.count(chunk.content()));
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(chunk.chunkRole() == ChunkRole.CONTEXT ? 80 : 20);
            if (chunk.startOffset() != null) {
                assertThat(chunk.content()).isEqualTo(fixture.text.substring(chunk.startOffset(), chunk.endOffset()));
            }
            assertThat(chunk.pageStart()).isNull();
            assertThat(chunk.pageEnd()).isNull();
            if (chunk.parentChunkId() != null) {
                var parent = chunks.findById(chunk.parentChunkId());
                assertThat(parent.chunkRole()).isEqualTo(ChunkRole.CONTEXT);
                assertThat(parent.revisionId()).isEqualTo(chunk.revisionId());
            }
        });
        if (mode == ChunkingMode.FLAT) assertThat(saved).allMatch(c -> c.chunkRole() == ChunkRole.RETRIEVAL && c.parentChunkId() == null);
        else assertThat(saved).anyMatch(c -> c.chunkRole() == ChunkRole.CONTEXT);
    }

    @Test void textParserUsesTheSameChunkerAndUnknownHeadingsRemainNull() throws Exception {
        jdbc.update("DELETE FROM knowledge_document");
        jdbc.update("DELETE FROM knowledge_base");
        jdbc.update("DELETE FROM file_asset");
        fixture.configure("fixture.txt", "普通文本第一段内容。".repeat(15) + "\n\n第二段没有标题");
        createDocument();
        var result = builds.build("owner", documentId);
        var saved = chunks.findByRevisionId(result.revisionId());
        assertThat(saved).allSatisfy(c -> assertThat(c.headingPath()).isNull());
        assertThat(fixture.vectors).hasSize(saved.size());
    }

    @Test void countFailureOccursAtChunkStageBeforeAnyDatabaseOrVectorWrite() {
        assertThatThrownBy(() -> limited.build("owner", documentId, ChunkingMode.PARENT_CHILD))
                .isInstanceOf(KnowledgeBuildException.class).extracting("stage").isEqualTo(KnowledgeBuildStage.CHUNK);
        var revision = revisions.findByDocumentId(documentId).getFirst();
        assertThat(revision.revisionStatus()).isEqualTo(RevisionStatus.FAILED);
        assertThat(chunks.findByRevisionId(revision.id())).isEmpty();
        assertThat(fixture.vectors).isEmpty();
        assertThat(documents.findById(documentId).activeRevisionId()).isNull();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean Fixture chunkingFixture() { return new Fixture(); }
        @Bean KnowledgeBuildLifecycleService chunkingLifecycle(KnowledgeDocumentRepository documents,
                KnowledgeRevisionRepository revisions, KnowledgeChunkRepository chunks, Clock clock) {
            return new KnowledgeBuildLifecycleService(documents, revisions, chunks, clock);
        }
        @Bean KnowledgeIndexBuildService chunkingBuild(Fixture fixture, KnowledgeDocumentRepository documents,
                KnowledgeChunkService chunks, KnowledgeBuildLifecycleService lifecycle) {
            return build(fixture, documents, chunks, lifecycle, 10000);
        }
        @Bean KnowledgeIndexBuildService limitedChunkingBuild(Fixture fixture, KnowledgeDocumentRepository documents,
                KnowledgeChunkService chunks, KnowledgeBuildLifecycleService lifecycle) {
            return build(fixture, documents, chunks, lifecycle, 2);
        }
        private static KnowledgeIndexBuildService build(Fixture fixture, KnowledgeDocumentRepository documents,
                KnowledgeChunkService chunks, KnowledgeBuildLifecycleService lifecycle, int maxChunks) {
            var router = new KnowledgeProcessingRouter(List.of(new TextKnowledgeDocumentParser(1_000_000),
                    new MarkdownKnowledgeDocumentParser(1_000_000)), List.of(new TokenBudgetKnowledgeChunker(ChunkingMode.FLAT),
                    new TokenBudgetKnowledgeChunker(ChunkingMode.PARENT_CHILD)));
            return new KnowledgeIndexBuildService(fixture.files, documents, router, new KnowledgeSourceReader(1_000_000),
                    new KnowledgeChunkGroupWriter(chunks), fixture, lifecycle,
                    new ChunkingOptions(20, 0, 0, maxChunks, true, ".。?!\n", 80, 3), 3);
        }
    }

    static final class Fixture implements KnowledgeVectorIndex {
        final FileContentService files = mock(FileContentService.class);
        final List<KnowledgeVectorEntry> vectors = new ArrayList<>();
        final List<Integer> batchSizes = new ArrayList<>();
        String name, text, hash;
        byte[] bytes;
        boolean closed;
        void configure(String name, String text) throws Exception {
            reset(files); vectors.clear(); batchSizes.clear(); closed = false;
            this.name = name; this.text = text; bytes = text.getBytes(StandardCharsets.UTF_8);
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            when(files.requireOwnedProcessable("owner", "file")).thenReturn(new ProcessableFileAsset(name, "text/plain"));
            when(files.openForProcessing("owner", "file")).thenAnswer(invocation -> new FileContentResource(
                    "file", name, "text/plain", bytes.length, hash, new ByteArrayInputStream(bytes) {
                        @Override public void close() { closed = true; }
                    }));
        }
        @Override public void add(List<KnowledgeVectorEntry> entries) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            vectors.addAll(entries); batchSizes.add(entries.size());
        }
        @Override public void deleteByRevisionId(String revisionId) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            vectors.removeIf(entry -> entry.chunk().revisionId().equals(revisionId));
        }
    }
}
