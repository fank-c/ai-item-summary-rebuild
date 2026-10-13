package com.item.aisummary.application.knowledge.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class FileFormatTest {
    @Test
    void routesByLastExtensionWithoutClaimingContentDetection() {
        assertThat(FileFormat.fromFileName("资料.PDF")).isEqualTo(FileFormat.PDF);
        assertThat(FileFormat.fromFileName("a.txt.DOCX")).isEqualTo(FileFormat.DOCX);
        for (String name : java.util.List.of("a", "a.", "a.doc", "a.txt.exe")) {
            assertThatIllegalArgumentException().isThrownBy(() -> FileFormat.fromFileName(name));
        }
    }
}
