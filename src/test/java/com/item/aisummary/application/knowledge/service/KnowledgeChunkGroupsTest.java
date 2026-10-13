package com.item.aisummary.application.knowledge.service;

import com.item.aisummary.application.file.port.FileRepository;
import com.item.aisummary.application.knowledge.processing.ChunkDraft;
import com.item.aisummary.application.knowledge.processing.ChunkGroupDraft;
import com.item.aisummary.application.knowledge.processing.SourceLocation;
import com.item.aisummary.domain.file.model.FileAsset;
import com.item.aisummary.domain.knowledge.enums.ChunkRole;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class KnowledgeChunkGroupsTest {
    @Autowired KnowledgeBaseService bases;
    @Autowired KnowledgeDocumentService documents;
    @Autowired KnowledgeRevisionService revisions;
    @Autowired KnowledgeChunkService chunks;
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
        var saved = chunks.saveGroups(revisionId, 0, List.of(
                new ChunkGroupDraft(null, List.of(draft("平铺"))),
                new ChunkGroupDraft(draft("父块"), List.of(draft("子块 A"), draft("子块 B")))));
        assertThat(saved).extracting("sequenceNo").containsExactly(0, 1, 2, 3);
        assertThat(saved).extracting("chunkRole").containsExactly(
                ChunkRole.RETRIEVAL, ChunkRole.CONTEXT, ChunkRole.RETRIEVAL, ChunkRole.RETRIEVAL);
        assertThat(saved.get(2).parentChunkId()).isEqualTo(saved.get(1).id());
        assertThat(saved.get(3).parentChunkId()).isEqualTo(saved.get(1).id());
        assertThat(saved.get(0).startOffset()).isEqualTo(1000);
        chunks.saveGroups(revisionId, 4, List.of(new ChunkGroupDraft(null, List.of(draft("尾块")))));
        assertThat(revisions.findById(revisionId).chunkCount()).isEqualTo(5);
    }

    @Test void lateGroupFailureRollsBackEarlierGroupAndCount() {
        jdbc.execute("ALTER TABLE knowledge_chunk ADD CONSTRAINT fixture_group_guard CHECK (sequence_no <> 2)");
        try {
            assertThatThrownBy(() -> chunks.saveGroups(revisionId, 0, List.of(
                    new ChunkGroupDraft(null, List.of(draft("平铺"))),
                    new ChunkGroupDraft(draft("父块"), List.of(draft("子块"))))));
            assertThat(chunks.findByRevisionId(revisionId)).isEmpty();
            assertThat(revisions.findById(revisionId).chunkCount()).isZero();
        } finally { jdbc.execute("ALTER TABLE knowledge_chunk DROP CONSTRAINT fixture_group_guard"); }
    }

    @Test void rejectsSequenceOverflowAndEmptyInputBeforeAnyWrite() {
        var group = new ChunkGroupDraft(draft("父块"), List.of(draft("子块")));
        assertThatIllegalArgumentException().isThrownBy(() -> chunks.saveGroups(revisionId, Integer.MAX_VALUE, List.of(group)));
        assertThatIllegalArgumentException().isThrownBy(() -> chunks.saveGroups(revisionId, -1, List.of(group)));
        assertThatIllegalArgumentException().isThrownBy(() -> chunks.saveGroups(revisionId, 0, List.of()));
        assertThat(chunks.findByRevisionId(revisionId)).isEmpty();
    }

    private static ChunkDraft draft(String content) {
        return new ChunkDraft(content, null, null, new SourceLocation(1000, 1100, 2, 3));
    }
}
