package com.item.aisummary.application.knowledge.indexing.chunk;

import com.item.aisummary.application.file.port.FileRepository;
import com.item.aisummary.application.knowledge.service.KnowledgeBaseService;
import com.item.aisummary.application.knowledge.service.KnowledgeChunkService;
import com.item.aisummary.application.knowledge.service.KnowledgeDocumentService;
import com.item.aisummary.application.knowledge.service.KnowledgeRevisionService;
import com.item.aisummary.domain.knowledge.value.Heading;
import com.item.aisummary.domain.knowledge.value.SourceLocation;
import com.item.aisummary.domain.file.model.FileAsset;
import com.item.aisummary.domain.knowledge.enums.ChunkRole;
import com.item.aisummary.domain.knowledge.enums.RevisionStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(KnowledgeChunkGroupWriterTest.WriterTestConfiguration.class)
class KnowledgeChunkGroupWriterTest {
    @Autowired KnowledgeBaseService bases;
    @Autowired KnowledgeDocumentService documents;
    @Autowired KnowledgeRevisionService revisions;
    @Autowired KnowledgeChunkService chunks;
    @Autowired KnowledgeChunkGroupWriter writer;
    @Autowired FileRepository files;
    @Autowired JdbcTemplate jdbc;
    private String revisionId;

    @BeforeEach void createVersion() {
        jdbc.update("DELETE FROM knowledge_chunk");
        jdbc.update("DELETE FROM knowledge_document_revision");
        jdbc.update("DELETE FROM knowledge_document");
        jdbc.update("DELETE FROM knowledge_base");
        jdbc.update("DELETE FROM file_asset");
        Instant now = Instant.parse("2026-01-02T03:04:05.123456Z");
        files.insert(new FileAsset("file", "fixture.txt", "text/plain", 3, "minio", "files/fixture.txt", null,
                1, null, "completed", "owner", "owner", now, now));
        var document = documents.register("owner", bases.create("资料").id(), "file");
        revisionId = revisions.create(document.id()).id();
    }

    @Test void mapsGroupsToExistingSameRevisionParentContractAndCountsAllRoles() {
        var saved = writer.saveGroups(revisionId, 0, List.of(
                new ChunkGroupDraft(null, List.of(draft("平铺"))),
                new ChunkGroupDraft(draft("父块"), List.of(draft("子块 A"), draft("子块 B")))));
        assertThat(saved).extracting("sequenceNo").containsExactly(0, 1, 2, 3);
        assertThat(saved).extracting("chunkRole").containsExactly(
                ChunkRole.RETRIEVAL, ChunkRole.CONTEXT, ChunkRole.RETRIEVAL, ChunkRole.RETRIEVAL);
        assertThat(saved.get(2).parentChunkId()).isEqualTo(saved.get(1).id());
        assertThat(saved.get(3).parentChunkId()).isEqualTo(saved.get(1).id());
        assertThat(saved.get(0).startOffset()).isEqualTo(1000);
        writer.saveGroups(revisionId, 4, List.of(new ChunkGroupDraft(null, List.of(draft("尾块")))));
        assertThat(revisions.findById(revisionId).chunkCount()).isEqualTo(5);
    }

    @Test void lateGroupFailureRollsBackEarlierGroupAndCount() {
        jdbc.execute("ALTER TABLE knowledge_chunk ADD CONSTRAINT fixture_group_guard CHECK (sequence_no <> 2)");
        try {
            assertThatThrownBy(() -> writer.saveGroups(revisionId, 0, List.of(
                    new ChunkGroupDraft(null, List.of(draft("平铺"))),
                    new ChunkGroupDraft(draft("父块"), List.of(draft("子块"))))));
            assertThat(chunks.findByRevisionId(revisionId)).isEmpty();
            assertThat(revisions.findById(revisionId).chunkCount()).isZero();
        } finally { jdbc.execute("ALTER TABLE knowledge_chunk DROP CONSTRAINT fixture_group_guard"); }
    }

    @Test void snapshotsLocationHeadingAndContentAndRejectsNonBuildingVersion() {
        var location = new SourceLocation(10, 20, 2, 3);
        var heading = new Heading(1, "标题");
        var headings = new ArrayList<>(List.of(heading));
        var context = new ChunkDraft("父正文", headings, 7, location);
        var child = new ChunkDraft("子正文", headings, 8, location);
        headings.clear();
        var saved = writer.saveGroups(revisionId, 0, List.of(new ChunkGroupDraft(context, List.of(child))));
        assertThat(saved.get(0).content()).isEqualTo("父正文");
        assertThat(saved.get(0).headingPath()).containsExactly(heading);
        assertThat(saved.get(0).startOffset()).isEqualTo(10);
        assertThat(saved.get(1).content()).isEqualTo("子正文");
        var unknown = writer.saveGroups(revisionId, 2, List.of(new ChunkGroupDraft(null,
                List.of(new ChunkDraft("未知位置", new ArrayList<>(List.of(heading)), null, null))))).getFirst();
        assertThat(unknown.startOffset()).isNull();
        assertThat(unknown.endOffset()).isNull();
        assertThat(unknown.content()).isEqualTo("未知位置");
        assertThat(unknown.headingPath()).containsExactly(heading);
        jdbc.update("UPDATE knowledge_document_revision SET revision_status=? WHERE id=?", RevisionStatus.READY.name().toLowerCase(), revisionId);
        new ChunkGroupDraft(null, List.of(new ChunkDraft("仍可构造", null, null, null)));
        assertThatThrownBy(() -> writer.saveGroups(revisionId, 2, List.of(new ChunkGroupDraft(null, List.of(draft("拒绝"))))))
                .isInstanceOf(KnowledgeConflictException.class)
                .extracting("code").isEqualTo("REVISION_NOT_BUILDING");
    }

    @Test void rejectsSequenceOverflowAndEmptyInputBeforeAnyWrite() {
        var group = new ChunkGroupDraft(draft("父块"), List.of(draft("子块")));
        assertThatIllegalArgumentException().isThrownBy(() -> writer.saveGroups(revisionId, Integer.MAX_VALUE, List.of(group)));
        assertThatIllegalArgumentException().isThrownBy(() -> writer.saveGroups(revisionId, -1, List.of(group)));
        assertThatIllegalArgumentException().isThrownBy(() -> writer.saveGroups(revisionId, 0, List.of()));
        assertThat(chunks.findByRevisionId(revisionId)).isEmpty();
    }

    private static ChunkDraft draft(String content) {
        return new ChunkDraft(content, null, null, new SourceLocation(1000, 1100, 2, 3));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class WriterTestConfiguration {
        @Bean
        KnowledgeChunkGroupWriter knowledgeChunkGroupWriter(KnowledgeChunkService chunks) {
            return new KnowledgeChunkGroupWriter(chunks);
        }
    }
}
