package com.item.aisummary.application.knowledge.indexing.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeChunkerContractsTest {
    @Test
    void acceptsFlatAndParentChildGroupsAndSnapshotsRetrievals() {
        var draft = new ChunkDraft("  片段\n", List.of(), 0, null);
        var children = new ArrayList<>(List.of(draft));
        var flat = new ChunkGroupDraft(null, children);
        var related = new ChunkGroupDraft(draft, children);
        children.clear();
        assertThat(flat.chunkCount()).isEqualTo(1);
        assertThat(related.chunkCount()).isEqualTo(2);
        assertThat(related.retrievals()).containsExactly(draft);
        assertThat(draft.headingPath()).isEmpty();
        assertThat(draft.content()).isEqualTo("  片段\n");
        assertThat(related.toString()).doesNotContain("片段");
    }

    @Test
    void rejectsInvalidDraftContentAndEmptyGroups() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ChunkDraft(" \n", null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChunkDraft("正文", null, -1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChunkGroupDraft(null, List.of()));
    }
}
