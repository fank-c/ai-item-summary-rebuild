package com.item.aisummary.application.knowledge.processing;

import com.item.aisummary.domain.knowledge.value.Heading;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KnowledgeProcessingContractsTest {
    @Test void preservesContentAndOptionalMetadataWithoutInventingPositions() {
        var headings = new ArrayList<>(List.of(new Heading(1, "章节")));
        var block = new ContentBlock(0, ContentBlockType.CODE, "  正文\n", headings, null, true);
        var blocks = new ArrayList<>(List.of(block));
        var parsed = new ParsedDocument(blocks);
        headings.clear();
        blocks.clear();
        assertThat(parsed.blocks()).containsExactly(block);
        assertThat(block.text()).isEqualTo("  正文\n");
        assertThat(block.headingPath()).containsExactly(new Heading(1, "章节"));
        assertThat(block.source()).isNull();
        assertThat(block.boundaryBefore()).isTrue();
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> parsed.blocks().clear());
        assertThat(parsed.toString()).doesNotContain("正文", "章节");
        assertThat(block.toString()).doesNotContain("正文", "章节");
    }

    @Test void acceptsFlatAndParentChildGroupsAndSnapshotsRetrievals() {
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

    @Test void rejectsInvalidStructureAndUnreliableRanges() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ParsedDocument(List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ParsedDocument(List.of(block(1))));
        assertThatIllegalArgumentException().isThrownBy(() -> new ParsedDocument(List.of(block(0), block(0))));
        assertThatIllegalArgumentException().isThrownBy(() -> new ContentBlock(-1, ContentBlockType.TEXT, "正文", null, null, false));
        assertThatIllegalArgumentException().isThrownBy(() -> new ContentBlock(0, null, "正文", null, null, false));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChunkDraft(" \n", null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChunkDraft("正文", null, -1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChunkGroupDraft(null, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceLocation(1, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceLocation(2, 1, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceLocation(null, null, 0, 1));
        assertThat(new SourceLocation(1000, 1200, 2, 3).startOffset()).isEqualTo(1000);
    }

    @Test void formatRoutingUsesOnlyLastExtensionWithoutClaimingContentDetection() {
        assertThat(FileFormat.fromFileName("资料.PDF")).isEqualTo(FileFormat.PDF);
        assertThat(FileFormat.fromFileName("a.txt.DOCX")).isEqualTo(FileFormat.DOCX);
        for (String name : List.of("a", "a.", "a.doc", "a.txt.exe")) {
            assertThatIllegalArgumentException().isThrownBy(() -> FileFormat.fromFileName(name));
        }
    }

    private static ContentBlock block(int ordinal) {
        return new ContentBlock(ordinal, ContentBlockType.TEXT, "正文", null, null, false);
    }
}
