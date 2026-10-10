package com.item.aisummary.domain.knowledge.rule;

import com.item.aisummary.domain.knowledge.model.KnowledgeChunk;
import com.item.aisummary.domain.knowledge.value.Heading;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

/** 防止空正文、无效位置和外部修改标题集合进入持久化；偏移不受片段正文长度限制。 */
class KnowledgeRulesTest {
    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05.123456Z");

    @Test void normalizesNamesAndAcceptsTheColumnBoundary() {
        assertThat(KnowledgeRules.requireName("  学习资料  ")).isEqualTo("学习资料");
        assertThat(KnowledgeRules.requireName("文".repeat(100))).hasSize(100);
    }

    @Test void rejectsMissingOrOversizedNames() {
        for (String value : new String[] {null, "", " \t\n", "文".repeat(101)}) {
            assertThatIllegalArgumentException().isThrownBy(() -> KnowledgeRules.requireName(value));
        }
    }

    @Test void preservesBodyAndUnknownPositionsWithoutInventingMetadata() {
        KnowledgeChunk chunk = chunk(0, "  正文\n", null, null, null, null, null, null);
        assertThat(chunk.content()).isEqualTo("  正文\n");
        assertThat(chunk.headingPath()).isNull();
        assertThat(chunk.tokenCount()).isNull();
        assertThat(chunk.startOffset()).isNull();
        assertThat(chunk.endOffset()).isNull();
        assertThat(chunk.pageStart()).isNull();
        assertThat(chunk.pageEnd()).isNull();
    }

    @Test void acceptsFullTextOffsetsLargerThanTheChunkBodyAndSinglePageRanges() {
        KnowledgeChunk chunk = chunk(8, "短正文", List.of(new Heading(1, "说明")), 0, 1000, 1100, 3, 3);
        assertThat(chunk.startOffset()).isEqualTo(1000);
        assertThat(chunk.endOffset()).isEqualTo(1100);
        assertThat(chunk.pageEnd()).isEqualTo(3);
    }

    @Test void takesAnImmutableOrderedHeadingSnapshot() {
        var input = new ArrayList<>(List.of(new Heading(1, "总览"), new Heading(2, "细节\"与换行\n")));
        KnowledgeChunk chunk = chunk(0, "正文", input, null, null, null, null, null);
        input.clear();
        assertThat(chunk.headingPath()).containsExactly(new Heading(1, "总览"), new Heading(2, "细节\"与换行\n"));
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> chunk.headingPath().clear());
    }

    @Test void rejectsBlankBodyAndNegativeSequence() {
        for (String value : new String[] {null, "", " \t\n"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> chunk(0, value, null, null, null, null, null, null));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> chunk(-1, "正文", null, null, null, null, null, null));
    }

    @ParameterizedTest @MethodSource("invalidMetadata")
    void rejectsInvalidTokenOffsetsAndPages(Integer[] values) {
        assertThatIllegalArgumentException().isThrownBy(() ->
            chunk(0, "正文", null, values[0], values[1], values[2], values[3], values[4]));
    }

    static Stream<Arguments> invalidMetadata() {
        return Stream.of(
            new Integer[] {-1, null, null, null, null},
            new Integer[] {null, 0, null, null, null},
            new Integer[] {null, null, 2, null, null},
            new Integer[] {null, -1, 2, null, null},
            new Integer[] {null, 2, 2, null, null},
            new Integer[] {null, 3, 2, null, null},
            new Integer[] {null, null, null, 1, null},
            new Integer[] {null, null, null, null, 2},
            new Integer[] {null, null, null, 0, 2},
            new Integer[] {null, null, null, 3, 2}).map(values -> Arguments.of((Object) values));
    }

    @Test void rejectsMalformedHeadingEntries() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Heading(0, "标题"));
        assertThatIllegalArgumentException().isThrownBy(() -> new Heading(1, " \n"));
        var headings = new ArrayList<Heading>();
        headings.add(null);
        assertThatIllegalArgumentException().isThrownBy(() -> chunk(0, "正文", headings, null, null, null, null, null));
    }

    private static KnowledgeChunk chunk(int sequence, String body, List<Heading> headings, Integer tokens,
                                        Integer start, Integer end, Integer pageStart, Integer pageEnd) {
        return new KnowledgeChunk("chunk", "revision", sequence, body, headings, tokens, start, end,
            pageStart, pageEnd, NOW);
    }
}
