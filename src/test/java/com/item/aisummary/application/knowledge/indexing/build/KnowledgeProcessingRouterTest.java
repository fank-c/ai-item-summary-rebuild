package com.item.aisummary.application.knowledge.indexing.build;

import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingMode;
import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.application.knowledge.indexing.parse.KnowledgeDocumentParser;
import com.item.aisummary.application.knowledge.indexing.chunk.KnowledgeChunker;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 策略路由注册表（KnowledgeProcessingRouter）单元测试。
 *
 * <p>验证解析格式（FileFormat）与分块模式（ChunkingMode）的独立策略路由查找、
 * 杜绝重复注册与模糊选择、以及未注册时提供可由 HTTP 边界识别的能力不可用编码。
 */
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
        assertThatThrownBy(() -> router.requireParser(FileFormat.PDF))
                .isInstanceOf(KnowledgeBuildUnavailableException.class)
                .extracting("code").isEqualTo("KNOWLEDGE_PARSER_UNAVAILABLE");
        assertThatThrownBy(() -> router.requireChunker(ChunkingMode.PARENT_CHILD))
                .isInstanceOf(KnowledgeBuildUnavailableException.class)
                .extracting("code").isEqualTo("KNOWLEDGE_CHUNKER_UNAVAILABLE");
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
