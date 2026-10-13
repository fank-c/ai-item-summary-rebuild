package com.item.aisummary.application.knowledge.indexing.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class ChunkingOptionsTest {

    @Test
    void createsChunkingOptionsWithAllEightFields() {
        var options = new ChunkingOptions(512, 200, 0, 10000, true, ".?!\n", 2048, 64);

        assertThat(options.chunkSize()).isEqualTo(512);
        assertThat(options.minChunkSizeChars()).isEqualTo(200);
        assertThat(options.minChunkLengthToEmbed()).isZero();
        assertThat(options.maxNumChunks()).isEqualTo(10000);
        assertThat(options.keepSeparator()).isTrue();
        assertThat(options.punctuationMarks()).isEqualTo(".?!\n");
        assertThat(options.parentChunkSize()).isEqualTo(2048);
        assertThat(options.chunkOverlap()).isEqualTo(64);
    }

    @Test
    void defaultsParentSizeAndZeroOverlapInSixArgConstructor() {
        var options = new ChunkingOptions(512, 200, 0, 10000, false, "!?");

        assertThat(options.chunkSize()).isEqualTo(512);
        assertThat(options.parentChunkSize()).isEqualTo(512);
        assertThat(options.chunkOverlap()).isZero();
        assertThat(options.keepSeparator()).isFalse();
    }

    @Test
    void allowsParentChunkSizeEqualToChunkSizeAndZeroOverlap() {
        var options = new ChunkingOptions(100, 0, 0, 10, true, ".", 100, 0);

        assertThat(options.chunkSize()).isEqualTo(100);
        assertThat(options.parentChunkSize()).isEqualTo(100);
        assertThat(options.chunkOverlap()).isZero();
    }

    @Test
    void rejectsParentChunkSizeSmallerThanChunkSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChunkingOptions(512, 0, 0, 10, true, ".", 511, 0))
                .withMessageContaining("父块尺寸必须大于或等于子块尺寸");
    }

    @Test
    void rejectsNegativeChunkOverlap() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChunkingOptions(512, 0, 0, 10, true, ".", 512, -1))
                .withMessageContaining("重叠尺寸必须满足 0 <= chunkOverlap < chunkSize");
    }

    @Test
    void rejectsChunkOverlapGreaterThanOrEqualToChunkSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChunkingOptions(512, 0, 0, 10, true, ".", 1024, 512))
                .withMessageContaining("重叠尺寸必须满足 0 <= chunkOverlap < chunkSize");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChunkingOptions(512, 0, 0, 10, true, ".", 1024, 600))
                .withMessageContaining("重叠尺寸必须满足 0 <= chunkOverlap < chunkSize");
    }

    @Test
    void rejectsInvalidOriginalConstraints() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChunkingOptions(0, 0, 0, 10, true, ".", 0, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChunkingOptions(10, -1, 0, 10, true, ".", 10, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChunkingOptions(10, 0, 1, 10, true, ".", 10, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChunkingOptions(10, 0, 0, 0, true, ".", 10, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChunkingOptions(10, 0, 0, 10, true, null, 10, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChunkingOptions(10, 0, 0, 10, true, "", 10, 0));
    }
}
