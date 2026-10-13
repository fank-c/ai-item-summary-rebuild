package com.item.aisummary.infrastructure.knowledge.indexing.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.item.aisummary.application.knowledge.indexing.chunk.ChunkDraft;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkGroupDraft;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingMode;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingOptions;
import com.item.aisummary.application.knowledge.indexing.parse.ContentBlock;
import com.item.aisummary.application.knowledge.indexing.parse.ContentBlockType;
import com.item.aisummary.application.knowledge.indexing.parse.ParsedDocument;
import com.item.aisummary.domain.knowledge.value.Heading;
import com.item.aisummary.domain.knowledge.value.SourceLocation;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TokenBudgetKnowledgeChunkerTest {
    private static final Encoding ENCODING = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    private final TokenBudgetKnowledgeChunker flat = new TokenBudgetKnowledgeChunker(ChunkingMode.FLAT);
    private final TokenBudgetKnowledgeChunker related = new TokenBudgetKnowledgeChunker(ChunkingMode.PARENT_CHILD);

    @Test
    void retainsExactTextAndSingleBlockSourceWithoutTrimming() {
        String text = "  中文😀 first line\nsecond line。  ";
        var block = block(0, text, null, new SourceLocation(50, 50 + text.length(), 3, 3), false);
        var chunks = retrievals(flat.chunk(new ParsedDocument(List.of(block)), options(100, 200, 0)));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).isEqualTo(text);
        assertThat(chunks.getFirst().headingPath()).isNull();
        assertThat(chunks.getFirst().source()).isEqualTo(block.source());
        assertTokens(chunks, 100);
    }

    @Test
    void splitsLongTextIntoExactRangesAndNeverDropsShortTail() {
        String text = "上传数据库失败后需清理对象。".repeat(40) + "尾";
        var blocks = new ParsedDocument(List.of(block(0, text, List.of(), new SourceLocation(200, 200 + text.length(), 1, 1), false)));
        var chunks = retrievals(flat.chunk(blocks, options(16, 64, 0)));
        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(String.join("", chunks.stream().map(ChunkDraft::content).toList())).isEqualTo(text);
        int end = 200;
        for (var chunk : chunks) {
            assertThat(chunk.source().startOffset()).isEqualTo(end);
            end = chunk.source().endOffset();
            assertThat(chunk.content()).isEqualTo(text.substring(chunk.source().startOffset() - 200, end - 200));
            assertThat(chunk.source().pageStart()).isEqualTo(1);
            assertThat(chunk.headingPath()).isEmpty();
        }
        assertThat(end).isEqualTo(200 + text.length());
        assertTokens(chunks, 16);
    }

    @Test
    void prefersParagraphThenSentenceBoundariesRatherThanArbitraryCuts() {
        String text = "first paragraph.\n\nsecond paragraph has many words and continues here. "
                + "third sentence has even more words and continues here.";
        var chunks = retrievals(flat.chunk(document(text), options(20, 100, 0)));
        assertThat(chunks.getFirst().content()).endsWith("\n\n");
        assertTokens(chunks, 20);
        assertThat(String.join("", chunks.stream().map(ChunkDraft::content).toList())).isEqualTo(text);
    }

    @Test
    void keepsSpecialTokenLiteralsAndUnicodeIntactEvenWithSmallBudgets() {
        String text = "你好😀世界 <|endoftext|> e\u0301 👨‍👩‍👧‍👦 完成";
        var chunks = retrievals(flat.chunk(document(text), options(5, 30, 0)));
        assertThat(String.join("", chunks.stream().map(ChunkDraft::content).toList())).isEqualTo(text);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.content()).doesNotContain("\ufffd");
            assertValidSurrogates(chunk.content());
        });
        assertTokens(chunks, 5);
        assertThatIllegalArgumentException().isThrownBy(() -> flat.chunk(document("😀"), options(1, 4, 0)))
                .withMessageContaining("Token 预算");
        assertThatIllegalArgumentException().isThrownBy(() -> flat.chunk(document("bad\ud800text"), options(16, 32, 0)));
    }

    @Test
    void overlapRepeatsOnlySourceRangesAndAlwaysAddsNewContent() {
        String text = "这是一个用于验证切片重叠和进度的完整句子。".repeat(15);
        var chunks = retrievals(flat.chunk(new ParsedDocument(List.of(
                block(0, text, null, new SourceLocation(0, text.length(), null, null), false))), options(20, 60, 4)));
        int previousStart = -1;
        int coveredEnd = 0;
        boolean sawOverlap = false;
        for (var chunk : chunks) {
            int start = chunk.source().startOffset();
            int end = chunk.source().endOffset();
            assertThat(start).isGreaterThan(previousStart).isLessThanOrEqualTo(coveredEnd);
            assertThat(end).isGreaterThan(coveredEnd);
            assertThat(chunk.content()).isEqualTo(text.substring(start, end));
            if (start < coveredEnd) {
                sawOverlap = true;
                assertThat(ENCODING.countTokensOrdinary(text.substring(start, coveredEnd))).isLessThanOrEqualTo(4);
            }
            previousStart = start;
            coveredEnd = end;
        }
        assertThat(sawOverlap).isTrue();
        assertThat(coveredEnd).isEqualTo(text.length());
        assertTokens(chunks, 20);
    }

    @Test
    void mergesShortBlocksButDoesNotInventOffsetsAcrossUnknownSeparators() {
        var heading = List.of(new Heading(1, "说明"));
        var document = new ParsedDocument(List.of(
                block(0, "first", heading, new SourceLocation(0, 5, 2, 2), false),
                block(1, "second", heading, new SourceLocation(7, 13, 3, 3), false)));
        var chunks = retrievals(flat.chunk(document, options(50, 100, 0)));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).isEqualTo("first\n\nsecond");
        assertThat(chunks.getFirst().headingPath()).isEqualTo(heading);
        assertThat(chunks.getFirst().source()).isEqualTo(new SourceLocation(null, null, 2, 3));
    }

    @Test
    void respectsHardBoundariesHeadingChangesAndUnknownVsEmptyHeadingPaths() {
        var one = List.of(new Heading(1, "一"));
        var two = List.of(new Heading(1, "二"));
        var document = new ParsedDocument(List.of(
                block(0, "A", one, null, false), block(1, "B", one, null, true),
                block(2, "C", two, null, false), block(3, "D", null, null, false),
                block(4, "E", List.of(), null, false)));
        var chunks = retrievals(flat.chunk(document, options(50, 100, 3)));
        assertThat(chunks).extracting(ChunkDraft::content).containsExactly("A", "B", "C", "D", "E");
        assertThat(chunks.get(3).headingPath()).isNull();
        assertThat(chunks.get(4).headingPath()).isEmpty();
    }

    @Test
    void partialMultiPageAndNonOneToOneTextDoNotFabricateLocation() {
        String text = "文字内容足够长以便产生多个片段。".repeat(12);
        var unknownMapping = new ParsedDocument(List.of(block(0, text, null,
                new SourceLocation(10, 20, 2, 4), false)));
        var chunks = retrievals(flat.chunk(unknownMapping, options(15, 30, 0)));
        assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(chunk -> assertThat(chunk.source()).isNull());
        var whole = retrievals(flat.chunk(unknownMapping, options(1000, 1000, 0))).getFirst();
        assertThat(whole.source()).isEqualTo(new SourceLocation(10, 20, 2, 4));
    }

    @Test
    void parentChildSharesKernelAndChildrenStayWithinTheirOwnParent() {
        String text = "父子切片保存来源并独立计算分词。".repeat(35);
        var source = new SourceLocation(100, 100 + text.length(), 7, 7);
        var document = new ParsedDocument(List.of(block(0, text, List.of(), source, false)));
        var groups = related.chunk(document, options(20, 75, 4));
        assertThat(groups).hasSizeGreaterThan(1);
        StringBuilder parentCoverage = new StringBuilder();
        for (var group : groups) {
            var parent = group.context();
            if (parent == null) {
                assertThat(group.retrievals()).hasSize(1);
                parentCoverage.append(group.retrievals().getFirst().content());
                continue;
            }
            parentCoverage.append(parent.content());
            assertTokens(List.of(parent), 75);
            assertTokens(group.retrievals(), 20);
            int coveredEnd = parent.source().startOffset();
            for (var child : group.retrievals()) {
                assertThat(child.source().startOffset()).isBetween(parent.source().startOffset(), coveredEnd);
                assertThat(child.source().endOffset()).isGreaterThan(coveredEnd).isLessThanOrEqualTo(parent.source().endOffset());
                assertThat(child.content()).isEqualTo(text.substring(child.source().startOffset() - 100, child.source().endOffset() - 100));
                coveredEnd = child.source().endOffset();
            }
            assertThat(coveredEnd).isEqualTo(parent.source().endOffset());
        }
        assertThat(parentCoverage.toString()).isEqualTo(text);
        var shortGroup = related.chunk(document("短内容"), options(20, 75, 4)).getFirst();
        assertThat(shortGroup.context()).isNull();
        assertThat(shortGroup.retrievals()).hasSize(1);
    }

    @Test
    void codeAndTableStaySeparateAndUseLineFallbackWithoutInventingStructure() {
        String code = "  call_one();\n  call_two();\n  call_three();\n";
        var document = new ParsedDocument(List.of(
                block(0, "intro", null, null, false),
                new ContentBlock(1, ContentBlockType.CODE, code, null, null, false),
                new ContentBlock(2, ContentBlockType.TABLE, "a|b\n1|2\n3|4", null, null, false),
                block(3, "tail", null, null, false)));
        var chunks = retrievals(flat.chunk(document, new ChunkingOptions(8, 0, 0, 100, false, ".;\n", 50, 0)));
        assertThat(chunks.getFirst().content()).isEqualTo("intro");
        assertThat(chunks.getLast().content()).isEqualTo("tail");
        assertThat(String.join("", chunks.stream().skip(1).takeWhile(c -> c.content().contains("call_")).map(ChunkDraft::content).toList()))
                .isEqualTo(code);
        assertTokens(chunks, 8);
    }

    @Test
    void separatorFalseReplacesOnlyTextLineBreaksAndKeepsPunctuationAndPositions() {
        String text = "  first\nsecond。  ";
        var document = new ParsedDocument(List.of(block(0, text, null, new SourceLocation(0, text.length(), null, null), false)));
        var chunk = retrievals(flat.chunk(document, new ChunkingOptions(100, 0, 0, 100, false, "。\n", 200, 0))).getFirst();
        assertThat(chunk.content()).isEqualTo("  first second。  ");
        assertThat(chunk.source()).isEqualTo(new SourceLocation(0, text.length(), null, null));
        assertTokens(List.of(chunk), 100);
    }

    @Test
    void maximumCountsAllRolesAndFailsRatherThanPublishingTruncatedContent() {
        var document = document("必须保留全部正文内容。".repeat(40));
        var limited = new ChunkingOptions(10, 0, 0, 2, true, "。\n", 2000, 0);
        assertThatIllegalArgumentException().isThrownBy(() -> flat.chunk(document, limited)).withMessageContaining("片段数");
        assertThatIllegalArgumentException().isThrownBy(() -> related.chunk(document, limited)).withMessageContaining("片段数");
    }

    @Test
    void randomizedMixedScriptsKeepContentAndStrictTokenBudgets() {
        var random = new Random(13);
        String[] units = {"中", "word", "😀", "e\u0301", "。", "!", "1.25", "https://x.test", "_", "\n", " "};
        for (int sample = 0; sample < 25; sample++) {
            StringBuilder text = new StringBuilder("开始");
            for (int i = 0; i < 150; i++) text.append(units[random.nextInt(units.length)]);
            text.append("结束");
            var chunks = retrievals(flat.chunk(document(text.toString()), options(17, 50, 0)));
            assertThat(String.join("", chunks.stream().map(ChunkDraft::content).toList())).isEqualTo(text.toString());
            assertTokens(chunks, 17);
            chunks.forEach(c -> assertValidSurrogates(c.content()));
        }
    }

    @Test
    void supplementalPunctuationNeverSelectsAMidSurrogateBoundary() {
        String text = "中文😀正文内容😀结尾".repeat(8);
        var options = new ChunkingOptions(8, 0, 0, 1000, true, "😀", 24, 0);
        var chunks = retrievals(flat.chunk(document(text), options));
        assertThat(String.join("", chunks.stream().map(ChunkDraft::content).toList())).isEqualTo(text);
        chunks.forEach(c -> assertValidSurrogates(c.content()));
        assertTokens(chunks, 8);
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void nearFullOverlapStillTerminatesAndNeverRepeatsOnlyOldContent() {
        String text = "中文😀word和数字123。".repeat(80);
        var document = new ParsedDocument(List.of(block(0, text, null, new SourceLocation(0, text.length(), null, null), false)));
        var chunks = retrievals(flat.chunk(document, options(10, 50, 9)));
        int start = -1;
        int end = 0;
        for (var chunk : chunks) {
            assertThat(chunk.source().startOffset()).isGreaterThan(start).isLessThanOrEqualTo(end);
            assertThat(chunk.source().endOffset()).isGreaterThan(end);
            start = chunk.source().startOffset();
            end = chunk.source().endOffset();
        }
        assertThat(end).isEqualTo(text.length());
        assertTokens(chunks, 10);
    }

    @Test
    void offsetsCanResumeInsideASingleBlockAfterCrossBlockChunks() {
        String second = "后面一段内容保持它自己的精确位置。".repeat(20);
        var document = new ParsedDocument(List.of(
                block(0, "第一段", null, new SourceLocation(0, 3, null, null), false),
                block(1, second, null, new SourceLocation(500, 500 + second.length(), null, null), false)));
        var chunks = retrievals(flat.chunk(document, options(25, 80, 0)));
        assertThat(chunks.getFirst().source()).isNull();
        assertThat(chunks.stream().skip(1).filter(c -> c.source() != null)).isNotEmpty().allSatisfy(chunk -> {
            assertThat(chunk.content()).isEqualTo(second.substring(chunk.source().startOffset() - 500, chunk.source().endOffset() - 500));
        });
    }

    @Test
    void exactAllRoleLimitIsAcceptedAndOneLessIsRejected() {
        var document = document("完整片段计数与所有角色限制。".repeat(25));
        var groups = related.chunk(document, options(15, 50, 0));
        int exact = groups.stream().mapToInt(ChunkGroupDraft::chunkCount).sum();
        assertThat(related.chunk(document, new ChunkingOptions(15, 0, 0, exact, true, "。\n", 50, 0)))
                .hasSize(groups.size());
        assertThatIllegalArgumentException().isThrownBy(() -> related.chunk(document,
                new ChunkingOptions(15, 0, 0, exact - 1, true, "。\n", 50, 0)));
    }

    @Test
    void longWhitespaceNeverHidesMeaningfulContentAndLargeBlockCollectionsAreMapped() {
        String text = "首" + " ".repeat(1500) + "末";
        var whitespaceChunks = retrievals(flat.chunk(document(text), options(12, 40, 0)));
        assertThat(String.join("", whitespaceChunks.stream().map(ChunkDraft::content).toList()).replace(" ", "")).isEqualTo("首末");
        assertTokens(whitespaceChunks, 12);
        var blocks = new java.util.ArrayList<ContentBlock>();
        for (int i = 0; i < 2000; i++) blocks.add(block(i, "段落" + i, List.of(), null, false));
        var chunks = retrievals(flat.chunk(new ParsedDocument(blocks), options(96, 384, 0)));
        String joined = String.join("", chunks.stream().map(ChunkDraft::content).toList());
        assertThat(joined).isEqualTo(String.join("\n\n", blocks.stream().map(ContentBlock::text).toList()));
        assertTokens(chunks, 96);
    }

    private static ContentBlock block(int ordinal, String text, List<Heading> headings, SourceLocation source, boolean boundary) {
        return new ContentBlock(ordinal, ContentBlockType.TEXT, text, headings, source, boundary);
    }
    private static ParsedDocument document(String text) {
        return new ParsedDocument(List.of(block(0, text, null, null, false)));
    }
    private static ChunkingOptions options(int size, int parentSize, int overlap) {
        return new ChunkingOptions(size, 0, 0, 10000, true, ".?!;。？！；\n", parentSize, overlap);
    }
    private static List<ChunkDraft> retrievals(List<ChunkGroupDraft> groups) {
        return groups.stream().flatMap(g -> g.retrievals().stream()).toList();
    }
    private static void assertTokens(List<ChunkDraft> chunks, int max) {
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.tokenCount()).isEqualTo(ENCODING.countTokensOrdinary(c.content())).isLessThanOrEqualTo(max);
            assertThat(c.content()).isNotBlank();
        });
    }
    private static void assertValidSurrogates(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertThat(i + 1).isLessThan(text.length());
                assertThat(Character.isLowSurrogate(text.charAt(++i))).isTrue();
            } else assertThat(Character.isLowSurrogate(c)).isFalse();
        }
    }
}
