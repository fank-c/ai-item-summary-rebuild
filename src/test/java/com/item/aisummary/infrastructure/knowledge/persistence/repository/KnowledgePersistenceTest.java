package com.item.aisummary.infrastructure.knowledge.persistence.repository;

import com.item.aisummary.application.knowledge.port.KnowledgeBaseRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeChunkRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.domain.knowledge.enums.ChunkRole;
import com.item.aisummary.domain.knowledge.enums.DocumentIndexStatus;
import com.item.aisummary.domain.knowledge.enums.RevisionStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.model.KnowledgeBase;
import com.item.aisummary.domain.knowledge.model.KnowledgeChunk;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocument;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocumentRevision;
import com.item.aisummary.domain.knowledge.value.Heading;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeBaseEntity;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeChunkEntity;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeDocumentEntity;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeRevisionEntity;
import com.item.aisummary.infrastructure.knowledge.persistence.mapper.KnowledgeBaseMapper;
import com.item.aisummary.infrastructure.knowledge.persistence.mapper.KnowledgeChunkMapper;
import com.item.aisummary.infrastructure.knowledge.persistence.mapper.KnowledgeDocumentMapper;
import com.item.aisummary.infrastructure.knowledge.persistence.mapper.KnowledgeRevisionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 Mapper 验证所有字段、JSON 数组与联合唯一约束；结果仅代表 H2。 */
@SpringBootTest
class KnowledgePersistenceTest {
    @Autowired KnowledgeBaseRepository bases;
    @Autowired KnowledgeDocumentRepository documents;
    @Autowired KnowledgeRevisionRepository revisions;
    @Autowired KnowledgeChunkRepository chunks;
    @Autowired JdbcTemplate jdbc;
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05.123456Z");
    private static final Instant UPDATED = Instant.parse("2026-01-03T04:05:06.654321Z");

    @BeforeEach void resetKnowledge() {
        jdbc.update("DELETE FROM knowledge_chunk");
        jdbc.update("DELETE FROM knowledge_document_revision");
        jdbc.update("DELETE FROM knowledge_document");
        jdbc.update("DELETE FROM knowledge_base");
    }

    @Test void roundTripsBaseAndDocumentFieldsWithoutOwnerOrStatusFiltering() {
        var base = new KnowledgeBase("base", "同名", 7, CREATED, UPDATED);
        assertThat(bases.insert(base)).isEqualTo(base);
        bases.insert(new KnowledgeBase("another-base", "同名", 1, CREATED, CREATED));
        assertThat(bases.findAll()).hasSize(2);
        assertThat(bases.findById("base")).contains(base);
        assertThat(bases.rename("base", "新名称", CREATED)).isEqualTo(1);
        assertThat(bases.findById("base").orElseThrow()).isEqualTo(new KnowledgeBase("base", "新名称", 7, CREATED, CREATED));
        assertThat(bases.rename("missing", "名称", UPDATED)).isZero();
        var document = new KnowledgeDocument("document", "base", "file", 8, DocumentIndexStatus.INDEXED,
            "active", "previous-code", "失败摘要", CREATED, UPDATED);
        assertThat(documents.insert(document)).isEqualTo(document);
        assertThat(documents.findById("document")).contains(document);
        assertThat(documents.findByKnowledgeBaseAndFile("base", "file")).contains(document);
        assertThat(documents.findByKnowledgeBaseId("base")).containsExactly(document);
        assertThat(jdbc.queryForObject("SELECT create_at FROM knowledge_document WHERE id='document'", LocalDateTime.class))
            .isEqualTo(LocalDateTime.parse("2026-01-02T03:04:05.123456"));
    }

    @Test void mapsRevisionCreatedAtAndAllNullableLifecycleFields() {
        var revision = new KnowledgeDocumentRevision("revision", "document", 4, RevisionStatus.FAILED,
            "a".repeat(64), 2, "parsing", "fixture-code", "测试失败摘要",
            CREATED, UPDATED, UPDATED.plusSeconds(1), UPDATED.plusSeconds(2), UPDATED.plusSeconds(3));
        assertThat(revisions.insert(revision)).isEqualTo(revision);
        assertThat(revisions.findById("revision")).contains(revision);
        assertThat(revisions.findByDocumentId("document")).containsExactly(revision);
        assertThat(revisions.findMaxRevisionNumber("document")).isEqualTo(4);
        assertThat(revisions.findMaxRevisionNumber("empty")).isZero();
        assertThat(jdbc.queryForObject("SELECT created_at FROM knowledge_document_revision WHERE id='revision'", LocalDateTime.class))
            .isEqualTo(LocalDateTime.parse("2026-01-02T03:04:05.123456"));
        assertThat(revisions.updateChunkCount("revision", 3)).isZero();
    }

    @Test void preservesJsonArrayOrderQuotedUnicodeTitlesAndOptionalPositions() {
        List<Heading> headings = List.of(new Heading(1, "说明\"与反斜杠\\"), new Heading(2, "下一节\n😀"));
        var chunk = new KnowledgeChunk("chunk", "revision", 9, "  正文\n", headings, 12, 1000, 1200, 3, 4, CREATED);
        assertThat(chunks.insert(chunk)).isEqualTo(chunk);
        assertThat(chunks.findById("chunk")).contains(chunk);
        String raw = jdbc.queryForObject("SELECT heading_path FROM knowledge_chunk WHERE id='chunk'", String.class);
        var json = JsonMapper.shared().readTree(raw);
        assertThat(json.isArray()).isTrue();
        assertThat(json.size()).isEqualTo(2);
        assertThat(json.get(0).get("title").asString()).isEqualTo("说明\"与反斜杠\\");
        assertThat(json.get(1).get("level").asInt()).isEqualTo(2);
        chunks.insert(new KnowledgeChunk("unknown", "revision", 0, "未知位置", null, null, null, null, null, null, CREATED));
        chunks.insert(new KnowledgeChunk("empty-headings", "revision", 3, "空标题路径", List.of(), 0, 0, 1, 1, 1, CREATED));
        assertThat(chunks.findByRevisionId("revision")).extracting(KnowledgeChunk::sequenceNo).containsExactly(0, 3, 9);
        var unknown = chunks.findById("unknown").orElseThrow();
        assertThat(unknown.headingPath()).isNull();
        assertThat(unknown.tokenCount()).isNull();
        assertThat(unknown.startOffset()).isNull();
        assertThat(unknown.endOffset()).isNull();
        assertThat(unknown.pageStart()).isNull();
        assertThat(unknown.pageEnd()).isNull();
        assertThat(chunks.findById("empty-headings").orElseThrow().headingPath()).isEmpty();
        assertThat(chunks.countByRevisionId("revision")).isEqualTo(3);
        assertThat(chunks.countByRevisionIdAndRole("revision", ChunkRole.CONTEXT)).isZero();
        assertThat(chunks.countByRevisionIdAndRole("revision", ChunkRole.RETRIEVAL)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT create_at FROM knowledge_chunk WHERE id='chunk'", LocalDateTime.class))
            .isEqualTo(LocalDateTime.parse("2026-01-02T03:04:05.123456"));
    }

    @Test void roundTripsChunkRoleParentAndScopedSequenceLookupAndCountsAllRoles() {
        var context = canonicalChunk("context", "revision", null, ChunkRole.CONTEXT, 0);
        var child = canonicalChunk("child", "revision", "context", ChunkRole.RETRIEVAL, 1);
        var parentless = canonicalChunk("parentless", "revision", null, ChunkRole.RETRIEVAL, 2);
        chunks.insert(context);
        chunks.insert(child);
        chunks.insert(parentless);
        chunks.insert(canonicalChunk("other-revision", "other", null, ChunkRole.RETRIEVAL, 1));

        assertThat(chunks.findById("context")).contains(context);
        assertThat(chunks.findById("child")).contains(child);
        assertThat(chunks.findById("parentless")).contains(parentless);
        assertThat(chunks.findByRevisionIdAndSequenceNo("revision", 1)).contains(child);
        assertThat(chunks.findByRevisionIdAndSequenceNo("other", 1)).contains(
            canonicalChunk("other-revision", "other", null, ChunkRole.RETRIEVAL, 1));
        assertThat(chunks.findByRevisionIdAndSequenceNo("revision", 9)).isEmpty();
        assertThat(chunks.countByRevisionId("revision")).isEqualTo(3);
        assertThat(chunks.countByRevisionIdAndRole("revision", ChunkRole.CONTEXT)).isEqualTo(1);
        assertThat(chunks.countByRevisionIdAndRole("revision", ChunkRole.RETRIEVAL)).isEqualTo(2);
        assertThat(chunks.countByRevisionIdAndRole("other", ChunkRole.RETRIEVAL)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT chunk_role FROM knowledge_chunk WHERE id='context'", String.class))
            .isEqualTo("CONTEXT");
        assertThat(jdbc.queryForObject("SELECT chunk_role FROM knowledge_chunk WHERE id='child'", String.class))
            .isEqualTo("RETRIEVAL");
        assertThat(jdbc.queryForObject("SELECT parent_chunk_id FROM knowledge_chunk WHERE id='child'", String.class))
            .isEqualTo("context");
        assertThat(jdbc.queryForObject("SELECT parent_chunk_id FROM knowledge_chunk WHERE id='context'", String.class))
            .isNull();
    }

    @Test void databaseDocumentUniqueIndexIsTheFinalConflictGuard() {
        documents.insert(document("d1", "base", "file"));
        assertThatThrownBy(() -> documents.insert(document("d2", "base", "file")))
            .isInstanceOf(KnowledgeConflictException.class).extracting("code").isEqualTo("DOCUMENT_ALREADY_REGISTERED");
        documents.insert(document("d3", "another-base", "file"));
        assertThat(documents.findByKnowledgeBaseId("base")).hasSize(1);
    }

    @Test void databaseRevisionAndChunkUniqueIndexesRejectSameParentDuplicates() {
        revisions.insert(revision("r1", "document", 1));
        assertThatThrownBy(() -> revisions.insert(revision("r2", "document", 1)))
            .isInstanceOf(KnowledgeConflictException.class).extracting("code").isEqualTo("REVISION_NUMBER_CONFLICT");
        revisions.insert(revision("r3", "other-document", 1));
        chunks.insert(new KnowledgeChunk("c1", "r1", 0, "正文", null, null, null, null, null, null, CREATED));
        assertThatThrownBy(() -> chunks.insert(new KnowledgeChunk("c2", "r1", 0, "正文", null, null, null, null, null, null, CREATED)))
            .isInstanceOf(KnowledgeConflictException.class).extracting("code").isEqualTo("CHUNK_SEQUENCE_CONFLICT");
        assertThat(revisions.updateChunkCount("r1", 1)).isEqualTo(1);
        assertThat(revisions.findById("r1").orElseThrow().chunkCount()).isEqualTo(1);
        assertThat(revisions.findById("r1").orElseThrow().revisionStatus()).isEqualTo(RevisionStatus.BUILDING);
        assertThat(revisions.updateChunkCount("missing", 1)).isZero();
    }

    @Test void allRepositoriesReturnEmptyForMissingIds() {
        assertThat(bases.findById("missing")).isEmpty();
        assertThat(documents.findById("missing")).isEmpty();
        assertThat(revisions.findById("missing")).isEmpty();
        assertThat(chunks.findById("missing")).isEmpty();
    }

    @ParameterizedTest @ValueSource(ints = {0, 2})
    void rejectsUnexpectedInsertRowCounts(int count) {
        var baseMapper = mock(KnowledgeBaseMapper.class);
        var documentMapper = mock(KnowledgeDocumentMapper.class);
        var revisionMapper = mock(KnowledgeRevisionMapper.class);
        var chunkMapper = mock(KnowledgeChunkMapper.class);
        when(baseMapper.insert(any(KnowledgeBaseEntity.class))).thenReturn(count);
        when(documentMapper.insert(any(KnowledgeDocumentEntity.class))).thenReturn(count);
        when(revisionMapper.insert(any(KnowledgeRevisionEntity.class))).thenReturn(count);
        when(chunkMapper.insert(any(KnowledgeChunkEntity.class))).thenReturn(count);
        assertThatIllegalStateException().isThrownBy(() -> new MyBatisKnowledgeBaseRepository(baseMapper)
            .insert(new KnowledgeBase("base", "资料", 1, CREATED, CREATED)));
        assertThatIllegalStateException().isThrownBy(() -> new MyBatisKnowledgeDocumentRepository(documentMapper, mock(javax.sql.DataSource.class))
            .insert(document("document", "base", "file")));
        assertThatIllegalStateException().isThrownBy(() -> new MyBatisKnowledgeRevisionRepository(revisionMapper, mock(javax.sql.DataSource.class))
            .insert(revision("revision", "document", 1)));
        assertThatIllegalStateException().isThrownBy(() -> new MyBatisKnowledgeChunkRepository(chunkMapper)
            .insert(new KnowledgeChunk("chunk", "revision", 0, "正文", null, null, null, null, null, null, CREATED)));
    }

    private static KnowledgeChunk canonicalChunk(String id, String revision, String parent, ChunkRole role, int sequence) {
        return new KnowledgeChunk(id, revision, parent, role, sequence, "正文", null,
            null, null, null, null, null, CREATED);
    }

    private static KnowledgeDocument document(String id, String base, String file) {
        return new KnowledgeDocument(id, base, file, 1, DocumentIndexStatus.PENDING, null, null, null, CREATED, CREATED);
    }
    private static KnowledgeDocumentRevision revision(String id, String document, int number) {
        return new KnowledgeDocumentRevision(id, document, number, RevisionStatus.BUILDING, null, 0,
            null, null, null, CREATED, CREATED, null, null, null);
    }
}
