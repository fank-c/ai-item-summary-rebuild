package com.item.aisummary.application.knowledge.processing;

import com.item.aisummary.application.knowledge.port.KnowledgeChunker;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeProcessingRouterTest {
    @Test void selectsParserAndChunkerIndependentlyAndDoesNotInvokeAlgorithms() {
        var txt = parser(FileFormat.TXT);
        var md = parser(FileFormat.MD);
        var flat = chunker(ChunkingMode.FLAT);
        var parentChild = chunker(ChunkingMode.PARENT_CHILD);
        var router = new KnowledgeProcessingRouter(List.of(txt, md), List.of(flat, parentChild));
        assertThat(router.requireParser(FileFormat.TXT)).isSameAs(txt);
        assertThat(router.requireParser(FileFormat.MD)).isSameAs(md);
        assertThat(router.requireChunker(ChunkingMode.FLAT)).isSameAs(flat);
        assertThat(router.requireChunker(ChunkingMode.PARENT_CHILD)).isSameAs(parentChild);
        verify(txt, never()).parse(any());
        verify(flat, never()).chunk(any(), any());
    }

    @Test void rejectsAmbiguousRegistrationsInsteadOfPickingFirst() {
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeProcessingRouter(
                List.of(parser(FileFormat.TXT), parser(FileFormat.TXT)), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeProcessingRouter(
                List.of(), List.of(chunker(ChunkingMode.FLAT), chunker(ChunkingMode.FLAT))));
    }

    @Test void missingStrategiesFailExplicitlyWithoutFallback() {
        var router = new KnowledgeProcessingRouter(List.of(), List.of());
        assertThat(router.hasParsers()).isFalse();
        assertThat(router.hasChunkers()).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> router.requireParser(FileFormat.PDF));
        assertThatIllegalStateException().isThrownBy(() -> router.requireChunker(ChunkingMode.PARENT_CHILD));
    }

    private static KnowledgeDocumentParser parser(FileFormat format) {
        var parser = mock(KnowledgeDocumentParser.class);
        when(parser.format()).thenReturn(format);
        return parser;
    }

    private static KnowledgeChunker chunker(ChunkingMode mode) {
        var chunker = mock(KnowledgeChunker.class);
        when(chunker.mode()).thenReturn(mode);
        return chunker;
    }
}
