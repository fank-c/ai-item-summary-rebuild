package com.item.aisummary.infrastructure.knowledge.indexing.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.item.aisummary.application.knowledge.indexing.vector.KnowledgeVectorEntry;
import com.item.aisummary.domain.knowledge.enums.ChunkRole;
import com.item.aisummary.domain.knowledge.model.KnowledgeChunk;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

class SpringAiKnowledgeVectorIndexTest {
    private static final String BASE_ID = "00000000000000000000000000000001";
    private static final String DOC_ID = "00000000000000000000000000000002";
    private static final String FILE_ID = "00000000000000000000000000000003";
    private static final String REVISION_ID = "00000000000000000000000000000004";
    private static final String CHUNK_ID = "1234567890abcdef1234567890abcdef";
    private static final String SHA256 = "a".repeat(64);

    @Test
    void mapsRetrievalChunkToStableUuidAndRequiredMetadata() {
        VectorStore store = mock(VectorStore.class);
        SpringAiKnowledgeVectorIndex index = new SpringAiKnowledgeVectorIndex(store);
        index.add(List.of(entry(CHUNK_ID, null)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(store).add(documents.capture());
        assertThat(documents.getValue()).hasSize(1);
        Document document = documents.getValue().getFirst();
        assertThat(document.getId()).isEqualTo("12345678-90ab-cdef-1234-567890abcdef");
        assertThat(document.getText()).isEqualTo("indexed content");
        assertThat(document.getMetadata()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "knowledge_base_id", BASE_ID,
                "knowledge_document_id", DOC_ID,
                "revision_id", REVISION_ID,
                "file_id", FILE_ID,
                "knowledge_chunk_id", CHUNK_ID,
                "sequence_no", 7,
                "origin_name", "notes.txt",
                "mime", "text/plain",
                "source_sha256", SHA256,
                "chunk_role", "RETRIEVAL"));
    }

    @Test
    void includesParentWhenPresentAndDeletesByRevisionFilterOnly() {
        VectorStore store = mock(VectorStore.class);
        SpringAiKnowledgeVectorIndex index = new SpringAiKnowledgeVectorIndex(store);
        index.add(List.of(entry(CHUNK_ID, "fedcba0987654321fedcba0987654321")));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(store).add(documents.capture());
        assertThat(documents.getValue().getFirst().getMetadata())
                .containsEntry("parent_chunk_id", "fedcba0987654321fedcba0987654321");

        index.deleteByRevisionId(REVISION_ID);
        ArgumentCaptor<Filter.Expression> filter = ArgumentCaptor.forClass(Filter.Expression.class);
        verify(store).delete(filter.capture());
        assertThat(filter.getValue().toString()).contains("revision_id", REVISION_ID);
    }

    @Test
    void validatesBatchesAndUsesSafeErrorsWhenVectorStoreFails() {
        VectorStore store = mock(VectorStore.class);
        SpringAiKnowledgeVectorIndex index = new SpringAiKnowledgeVectorIndex(store);
        assertThatThrownBy(() -> index.add(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.deleteByRevisionId(" "))
                .isInstanceOf(IllegalArgumentException.class);

        RuntimeException providerFailure = new RuntimeException("secret-provider-message");
        doThrow(providerFailure).when(store).add(anyList());
        doThrow(providerFailure).when(store).delete(any(Filter.Expression.class));
        assertThatThrownBy(() -> index.add(List.of(entry(CHUNK_ID, null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("知识片段向量写入失败")
                .hasCause(providerFailure);
        assertThatThrownBy(() -> index.deleteByRevisionId(REVISION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("知识片段向量清理失败");
    }

    private static KnowledgeVectorEntry entry(String chunkId, String parentId) {
        KnowledgeChunk chunk = new KnowledgeChunk(
                chunkId,
                REVISION_ID,
                parentId,
                ChunkRole.RETRIEVAL,
                7,
                "indexed content",
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2026-01-01T00:00:00Z"));
        return new KnowledgeVectorEntry(
                BASE_ID, DOC_ID, FILE_ID, "notes.txt", "text/plain", SHA256, chunk);
    }
}
