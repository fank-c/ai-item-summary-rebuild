package com.item.aisummary.infrastructure.knowledge.indexing.parse;

import com.item.aisummary.application.file.content.FileContentResource;
import com.item.aisummary.application.file.content.FileContentService;
import com.item.aisummary.application.file.content.ProcessableFileAsset;
import com.item.aisummary.application.file.port.FileRepository;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildException;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildLifecycleService;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildResult;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildStage;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeIndexBuildService;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeProcessingRouter;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkDraft;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkGroupDraft;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingMode;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingOptions;
import com.item.aisummary.application.knowledge.indexing.chunk.KnowledgeChunkGroupWriter;
import com.item.aisummary.application.knowledge.indexing.chunk.KnowledgeChunker;
import com.item.aisummary.application.knowledge.indexing.parse.ContentBlock;
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
import com.item.aisummary.domain.file.model.FileAsset;
import com.item.aisummary.domain.knowledge.enums.ChunkRole;
import com.item.aisummary.domain.knowledge.enums.DocumentIndexStatus;
import com.item.aisummary.domain.knowledge.enums.RevisionStatus;
import com.item.aisummary.domain.knowledge.model.KnowledgeChunk;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocument;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocumentRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TXT 与 Markdown 解析构建集成测试。
 *
 * <p>在禁用 RAG runtime 的隔离 H2 环境下验证：
 * <ul>
 *   <li>真实 {@link TextKnowledgeDocumentParser} 与 {@link MarkdownKnowledgeDocumentParser} 产出的结构化内容块入库；</li>
 *   <li>无父级检索分块替身转换为 {@link ChunkDraft} 后的全局序号、标题路径与 UTF-16 原文位置映射回库；</li>
 *   <li>RETRIEVAL 角色片段向向量端口的缓冲写入，无网络交互；</li>
 *   <li>成功构建时的 READY 活动版本指针与文档状态推进；</li>
 *   <li>合法 SHA 但非法 UTF-8 字节的解析失败安全持久化、无切片/向量、活动指针保留以及输入流的受控释放。</li>
 * </ul>
 */
@SpringBootTest
@Import(KnowledgeTextParsingBuildIntegrationTest.TestConfig.class)
class KnowledgeTextParsingBuildIntegrationTest {
    private static final String ACTOR = "test-owner";
    private static final long MAX_SOURCE_SIZE = 20 * 1024 * 1024L;

    @Autowired KnowledgeIndexBuildService buildService;
    @Autowired KnowledgeBaseService baseService;
    @Autowired KnowledgeDocumentService documentService;
    @Autowired KnowledgeDocumentRepository documentRepository;
    @Autowired KnowledgeRevisionRepository revisionRepository;
    @Autowired KnowledgeChunkRepository chunkRepository;
    @Autowired FileRepository fileRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired FileContentServiceMock fileMock;
    @Autowired RecordingVectorIndex vectorIndex;

    @BeforeEach
    void resetDatabaseAndMocks() {
        jdbcTemplate.update("DELETE FROM knowledge_chunk");
        jdbcTemplate.update("DELETE FROM knowledge_document_revision");
        jdbcTemplate.update("DELETE FROM knowledge_document");
        jdbcTemplate.update("DELETE FROM knowledge_base");
        jdbcTemplate.update("DELETE FROM file_asset");
        fileMock.reset();
        vectorIndex.reset();
    }

    record ValidSample(
            FileFormat format,
            String fileName,
            String mime,
            String content) {
        @Override
        public String toString() {
            return format + " (" + fileName + ")";
        }
    }

    record RegisteredDocument(
            String baseId,
            String documentId,
            String fileId,
            String fileName,
            String mime,
            String sha256,
            long size) {}

    private RegisteredDocument registerDocument(
            String actor,
            String fileName,
            String mime,
            byte[] contentBytes,
            AtomicBoolean streamClosed) {
        String fileId = "file-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long size = contentBytes.length;
        String sha256 = sha256(contentBytes);
        Instant now = Instant.now();

        // 登记使用真实文件元数据校验；仅构建读取使用下面的文件内容替身。
        fileRepository.insert(new FileAsset(
                fileId, fileName, mime, size, "minio", "files/" + fileName, sha256,
                1, null, "completed", actor, actor, now, now));

        String baseId = baseService.create("测试知识库").id();
        String documentId = documentService.register(actor, baseId, fileId).id();

        // FileContentService mock 仅提供源文件元数据与真实流资源
        when(fileMock.files.requireOwnedProcessable(actor, fileId))
                .thenReturn(new ProcessableFileAsset(fileName, mime));
        when(fileMock.files.openForProcessing(actor, fileId))
                .thenAnswer(inv -> new FileContentResource(
                        fileId, fileName, mime, size, sha256,
                        new ByteArrayInputStream(contentBytes) {
                            @Override
                            public void close() throws IOException {
                                if (streamClosed != null) {
                                    streamClosed.set(true);
                                }
                                super.close();
                            }
                        }));

        return new RegisteredDocument(baseId, documentId, fileId, fileName, mime, sha256, size);
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    static Stream<ValidSample> validDocumentSamples() {
        return Stream.of(
                new ValidSample(
                        FileFormat.TXT,
                        "sample.txt",
                        "text/plain",
                        """
                        第一段纯文本内容。
                        单换行保留在段内。

                        第二段独立段落，包含特殊字符与 UTF-16 编码测试：Hello 世界 🚀。

                        第三段结尾。"""),
                new ValidSample(
                        FileFormat.MD,
                        "sample.md",
                        "text/markdown",
                        """
                        # 第一章 快速入门

                        Markdown 文档第一段，介绍基础功能。

                        ## 1.1 系统架构

                        ```java
                        public class Demo {
                            public static void main(String[] args) {}
                        }
                        ```

                        | 组件 | 描述 |
                        | --- | --- |
                        | Web | 前端展示 |

                        > 引用说明文本。

                        ## 1.2 部署说明

                        最后部署说明段落。""")
        );
    }

    @ParameterizedTest
    @MethodSource("validDocumentSamples")
    @DisplayName("真实 TXT/MD 解析接入 H2 构建，分块与向量使用替身")
    void buildsTxtAndMarkdownValidSamplesSuccessfully(ValidSample sample) throws Exception {
        byte[] contentBytes = sample.content().getBytes(StandardCharsets.UTF_8);
        AtomicBoolean streamClosed = new AtomicBoolean(false);
        RegisteredDocument registered = registerDocument(ACTOR, sample.fileName(), sample.mime(), contentBytes, streamClosed);

        // 手工 new 独立解析器，解析期望 ContentBlock 列表以供回库断言比对
        KnowledgeDocumentParser expectedParser = sample.format() == FileFormat.TXT
                ? new TextKnowledgeDocumentParser(MAX_SOURCE_SIZE)
                : new MarkdownKnowledgeDocumentParser(MAX_SOURCE_SIZE);
        KnowledgeSourceReader reader = new KnowledgeSourceReader(MAX_SOURCE_SIZE);
        PreparedSource preparedSource;
        try (var resource = new FileContentResource(
                registered.fileId(), registered.fileName(), registered.mime(), contentBytes.length, registered.sha256(),
                new ByteArrayInputStream(contentBytes))) {
            preparedSource = reader.read(resource, sample.format());
        }
        ParsedDocument parsedDoc = expectedParser.parse(preparedSource);
        List<ContentBlock> expectedBlocks = parsedDoc.blocks();

        // 执行真实构建编排
        KnowledgeBuildResult result = buildService.build(ACTOR, registered.documentId());

        // 1. 构建结果断言
        assertThat(result.documentId()).isEqualTo(registered.documentId());
        assertThat(result.revisionId()).isNotBlank();
        assertThat(result.chunkCount()).isEqualTo(expectedBlocks.size());
        assertThat(result.retrievalChunkCount()).isEqualTo(expectedBlocks.size());
        assertThat(result.oldRevisionCleanupCompleted()).isTrue();

        // 2. READY 活动指针与文档索引状态断言
        KnowledgeDocument document = documentRepository.findById(registered.documentId()).orElseThrow();
        assertThat(document.activeRevisionId()).isEqualTo(result.revisionId());
        assertThat(document.indexStatus()).isEqualTo(DocumentIndexStatus.INDEXED);
        assertThat(document.lastFailureCode()).isNull();
        assertThat(document.lastFailureMessage()).isNull();

        KnowledgeDocumentRevision revision = revisionRepository.findById(result.revisionId()).orElseThrow();
        assertThat(revision.revisionStatus()).isEqualTo(RevisionStatus.READY);
        assertThat(revision.chunkCount()).isEqualTo(expectedBlocks.size());
        assertThat(revision.sourceSha256()).isEqualTo(registered.sha256());
        assertThat(revision.completedAt()).isNotNull();
        assertThat(revision.publishedAt()).isNotNull();
        assertThat(revision.failureStage()).isNull();
        assertThat(revision.failureCode()).isNull();
        assertThat(revision.failureMessage()).isNull();

        // 3. 与真实解析结果比对正文、标题路径与处理后全文的 UTF-16 偏移。
        List<KnowledgeChunk> savedChunks = chunkRepository.findByRevisionId(result.revisionId());
        assertThat(savedChunks).hasSize(expectedBlocks.size());

        String normalizedContent = sample.content().replace("\r\n", "\n").replace('\r', '\n');
        for (int i = 0; i < expectedBlocks.size(); i++) {
            KnowledgeChunk chunk = savedChunks.get(i);
            ContentBlock block = expectedBlocks.get(i);

            assertThat(chunk.sequenceNo()).isEqualTo(i);
            assertThat(chunk.chunkRole()).isEqualTo(ChunkRole.RETRIEVAL);
            assertThat(chunk.parentChunkId()).isNull();
            assertThat(chunk.content()).isEqualTo(block.text());
            assertThat(chunk.headingPath()).isEqualTo(block.headingPath());
            assertThat(chunk.startOffset()).isEqualTo(block.source().startOffset());
            assertThat(chunk.endOffset()).isEqualTo(block.source().endOffset());

            // 来源 UTF-16 偏移切片回库检验
            assertThat(normalizedContent.substring(chunk.startOffset(), chunk.endOffset()))
                    .isEqualTo(chunk.content());

            // 未知 token / page
            assertThat(chunk.tokenCount()).isNull();
            assertThat(chunk.pageStart()).isNull();
            assertThat(chunk.pageEnd()).isNull();
        }

        // 4. RETRIEVAL 向量记录比对
        assertThat(vectorIndex.added).hasSize(expectedBlocks.size());
        for (int i = 0; i < expectedBlocks.size(); i++) {
            KnowledgeVectorEntry vectorEntry = vectorIndex.added.get(i);
            KnowledgeChunk chunk = savedChunks.get(i);

            assertThat(vectorEntry.documentId()).isEqualTo(registered.documentId());
            assertThat(vectorEntry.knowledgeBaseId()).isEqualTo(registered.baseId());
            assertThat(vectorEntry.fileId()).isEqualTo(registered.fileId());
            assertThat(vectorEntry.originName()).isEqualTo(registered.fileName());
            assertThat(vectorEntry.mime()).isEqualTo(registered.mime());
            assertThat(vectorEntry.sourceSha256()).isEqualTo(registered.sha256());
            assertThat(vectorEntry.chunk().id()).isEqualTo(chunk.id());
            assertThat(vectorEntry.chunk().chunkRole()).isEqualTo(ChunkRole.RETRIEVAL);
        }

        // 5. 资源关闭
        assertThat(streamClosed.get()).isTrue();
    }

    @Test
    @DisplayName("合法 SHA 但非合法 UTF-8 字节：在 PARSE 阶段失败，安全落库且无切片/向量、不发布、流正常关闭")
    void invalidUtf8WithLegitimateShaFailsParsingPersistsSafeFailureWithoutChunksOrVectors() {
        byte[] invalidBytes = new byte[] {(byte) 0xFF, (byte) 0xFE, 0x41, 0x42};
        AtomicBoolean streamClosed = new AtomicBoolean(false);
        RegisteredDocument registered = registerDocument(ACTOR, "corrupt.txt", "text/plain", invalidBytes, streamClosed);

        assertThatThrownBy(() -> buildService.build(ACTOR, registered.documentId()))
                .isInstanceOf(KnowledgeBuildException.class)
                .satisfies(ex -> {
                    var failure = (KnowledgeBuildException) ex;
                    assertThat(failure.stage()).isEqualTo(KnowledgeBuildStage.PARSE);
                    assertThat(failure.code()).isEqualTo("PARSE_FAILED");
                    assertThat(failure.getMessage()).isEqualTo("源文件解析失败");
                    assertThat(failure.cleanupCompleted()).isTrue();
                });

        // 失败版本安全持久化
        List<KnowledgeDocumentRevision> docRevisions = revisionRepository.findByDocumentId(registered.documentId());
        assertThat(docRevisions).hasSize(1);
        KnowledgeDocumentRevision failedRevision = docRevisions.getFirst();
        assertThat(failedRevision.revisionStatus()).isEqualTo(RevisionStatus.FAILED);
        assertThat(failedRevision.failureStage()).isEqualTo("PARSE");
        assertThat(failedRevision.failureCode()).isEqualTo("PARSE_FAILED");
        assertThat(failedRevision.failureMessage()).isEqualTo("源文件解析失败");
        assertThat(failedRevision.chunkCount()).isZero();
        assertThat(failedRevision.publishedAt()).isNull();
        assertThat(failedRevision.completedAt()).isNotNull();
        assertThat(failedRevision.cleanedAt()).isNotNull();

        // 文档状态更新为 FAILED，活动指针为空
        KnowledgeDocument document = documentRepository.findById(registered.documentId()).orElseThrow();
        assertThat(document.indexStatus()).isEqualTo(DocumentIndexStatus.FAILED);
        assertThat(document.activeRevisionId()).isNull();
        assertThat(document.lastFailureCode()).isEqualTo("PARSE_FAILED");
        assertThat(document.lastFailureMessage()).isEqualTo("源文件解析失败");

        // 无切片、无向量、未发布
        assertThat(chunkRepository.findByRevisionId(failedRevision.id())).isEmpty();
        assertThat(vectorIndex.added).isEmpty();

        // 流已关闭
        assertThat(streamClosed.get()).isTrue();
    }

    @Test
    @DisplayName("文档初始构建成功后重建失败：旧活动指针与 INDEXED 状态完整保留")
    void rebuildFailureWithInvalidUtf8PreservesExistingActiveRevisionPointer() {
        // 第一阶段：先成功构建首个版本
        byte[] validBytes = "首版初始正文内容。\n\n次段内容。".getBytes(StandardCharsets.UTF_8);
        AtomicBoolean firstStreamClosed = new AtomicBoolean(false);
        RegisteredDocument registered = registerDocument(ACTOR, "doc.txt", "text/plain", validBytes, firstStreamClosed);

        KnowledgeBuildResult initialResult = buildService.build(ACTOR, registered.documentId());
        String initialRevisionId = initialResult.revisionId();

        KnowledgeDocument docAfterFirstBuild = documentRepository.findById(registered.documentId()).orElseThrow();
        assertThat(docAfterFirstBuild.activeRevisionId()).isEqualTo(initialRevisionId);
        assertThat(docAfterFirstBuild.indexStatus()).isEqualTo(DocumentIndexStatus.INDEXED);
        int initialVectorsCount = vectorIndex.added.size();
        assertThat(initialVectorsCount).isGreaterThan(0);
        assertThat(firstStreamClosed.get()).isTrue();

        // 第二阶段：更新文件流返回非法 UTF-8（合法 SHA），执行重建
        byte[] invalidBytes = new byte[] {(byte) 0xFF, (byte) 0xFE, 0x58, 0x59};
        String invalidSha = sha256(invalidBytes);
        AtomicBoolean secondStreamClosed = new AtomicBoolean(false);

        when(fileMock.files.openForProcessing(ACTOR, registered.fileId()))
                .thenAnswer(inv -> new FileContentResource(
                        registered.fileId(), registered.fileName(), registered.mime(),
                        invalidBytes.length, invalidSha,
                        new ByteArrayInputStream(invalidBytes) {
                            @Override
                            public void close() throws IOException {
                                secondStreamClosed.set(true);
                                super.close();
                            }
                        }));

        assertThatThrownBy(() -> buildService.build(ACTOR, registered.documentId()))
                .isInstanceOf(KnowledgeBuildException.class)
                .satisfies(ex -> {
                    var failure = (KnowledgeBuildException) ex;
                    assertThat(failure.stage()).isEqualTo(KnowledgeBuildStage.PARSE);
                    assertThat(failure.code()).isEqualTo("PARSE_FAILED");
                });

        // 重建失败后：旧活动指针保留，文档状态仍为 INDEXED
        KnowledgeDocument docAfterRebuild = documentRepository.findById(registered.documentId()).orElseThrow();
        assertThat(docAfterRebuild.activeRevisionId()).isEqualTo(initialRevisionId);
        assertThat(docAfterRebuild.indexStatus()).isEqualTo(DocumentIndexStatus.INDEXED);
        assertThat(docAfterRebuild.lastFailureCode()).isEqualTo("PARSE_FAILED");
        assertThat(docAfterRebuild.lastFailureMessage()).isEqualTo("源文件解析失败");

        // 原版本依然 READY，新版本记录为 FAILED
        KnowledgeDocumentRevision initialRevision = revisionRepository.findById(initialRevisionId).orElseThrow();
        assertThat(initialRevision.revisionStatus()).isEqualTo(RevisionStatus.READY);

        List<KnowledgeDocumentRevision> allRevisions = revisionRepository.findByDocumentId(registered.documentId());
        assertThat(allRevisions).hasSize(2);
        KnowledgeDocumentRevision failedRebuildRevision = allRevisions.stream()
                .filter(r -> !r.id().equals(initialRevisionId))
                .findFirst()
                .orElseThrow();
        assertThat(failedRebuildRevision.revisionStatus()).isEqualTo(RevisionStatus.FAILED);
        assertThat(failedRebuildRevision.failureStage()).isEqualTo("PARSE");
        assertThat(failedRebuildRevision.failureCode()).isEqualTo("PARSE_FAILED");
        assertThat(failedRebuildRevision.publishedAt()).isNull();

        // 新版本无切片，向量库没有为新版本新增条目
        assertThat(chunkRepository.findByRevisionId(failedRebuildRevision.id())).isEmpty();
        assertThat(vectorIndex.added).hasSize(initialVectorsCount);

        // 第二个流正常关闭
        assertThat(secondStreamClosed.get()).isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        FileContentServiceMock fileContentServiceMock() {
            return new FileContentServiceMock();
        }

        @Bean
        RecordingVectorIndex recordingVectorIndex() {
            return new RecordingVectorIndex();
        }

        @Bean
        KnowledgeBuildLifecycleService knowledgeBuildLifecycleService(
                KnowledgeDocumentRepository documents,
                KnowledgeRevisionRepository revisions,
                KnowledgeChunkRepository chunks,
                Clock clock) {
            return new KnowledgeBuildLifecycleService(documents, revisions, chunks, clock);
        }

        @Bean
        KnowledgeChunkGroupWriter knowledgeChunkGroupWriter(KnowledgeChunkService chunks) {
            return new KnowledgeChunkGroupWriter(chunks);
        }

        @Bean
        KnowledgeIndexBuildService knowledgeIndexBuildService(
                FileContentServiceMock fileMock,
                KnowledgeDocumentRepository documents,
                KnowledgeChunkGroupWriter chunkWriter,
                RecordingVectorIndex vectorIndex,
                KnowledgeBuildLifecycleService lifecycle) {
            var txtParser = new TextKnowledgeDocumentParser(MAX_SOURCE_SIZE);
            var mdParser = new MarkdownKnowledgeDocumentParser(MAX_SOURCE_SIZE);
            var chunker = new TestChunker();
            var router = new KnowledgeProcessingRouter(
                    List.of(txtParser, mdParser),
                    List.of(chunker));
            var sourceReader = new KnowledgeSourceReader(MAX_SOURCE_SIZE);
            var options = new ChunkingOptions(512, 200, 0, 1000, true, ".?!\n");
            return new KnowledgeIndexBuildService(
                    fileMock.files, documents, router, sourceReader, chunkWriter, vectorIndex,
                    lifecycle, options, 100);
        }
    }

    static class FileContentServiceMock {
        final FileContentService files = mock(FileContentService.class);

        void reset() {
            org.mockito.Mockito.reset(files);
        }
    }

    static class RecordingVectorIndex implements KnowledgeVectorIndex {
        final List<KnowledgeVectorEntry> added = new ArrayList<>();
        final List<String> deletedRevisionIds = new ArrayList<>();

        @Override
        public void add(List<KnowledgeVectorEntry> entries) {
            added.addAll(entries);
        }

        @Override
        public void deleteByRevisionId(String revisionId) {
            deletedRevisionIds.add(revisionId);
        }

        void reset() {
            added.clear();
            deletedRevisionIds.clear();
        }
    }

    static class TestChunker implements KnowledgeChunker {
        @Override
        public ChunkingMode mode() {
            return ChunkingMode.FLAT;
        }

        @Override
        public List<ChunkGroupDraft> chunk(ParsedDocument document, ChunkingOptions options) {
            return document.blocks().stream()
                    .map(block -> new ChunkGroupDraft(
                            null,
                            List.of(new ChunkDraft(block.text(), block.headingPath(), null, block.source()))))
                    .toList();
        }
    }
}
