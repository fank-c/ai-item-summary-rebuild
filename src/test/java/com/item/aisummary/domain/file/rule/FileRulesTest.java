package com.item.aisummary.domain.file.rule;

import com.item.aisummary.domain.file.exception.FileValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 原始展示名称与存储 Key 分离；新 Key 同时遵守总长度和每个路径段的字节预算。 */
class FileRulesTest {
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    void maximumLengthOriginalNamesDoNotBecomeObjectKeySegments() {
        for (String name : new String[] {"a".repeat(251) + ".TXT", "文".repeat(251) + ".TXT",
                "𠀀".repeat(125) + "a.TXT"}) {
            assertThat(name.length()).isEqualTo(255);
            assertThat(FileRules.requireName(name, Set.of("txt"))).isEqualTo(name);
            String key = FileRules.objectKey("files", name, NOW);
            assertThat(key).matches("files/2026/10/08/[0-9a-f-]{36}\\.txt");
            assertThat(key.substring(key.lastIndexOf('/') + 1).getBytes(StandardCharsets.UTF_8)).hasSize(40);
        }
    }

    @Test
    void eachPrefixSegmentMustFit255Utf8Bytes() {
        String prefix = "中".repeat(85);
        assertThat(FileRules.objectKey(prefix, "a.txt", NOW)).startsWith(prefix + "/2026/10/08/");
        assertThatThrownBy(() -> FileRules.objectKey(prefix + "a", "a.txt", NOW))
            .isInstanceOf(FileValidationException.class)
            .hasMessage("对象 Key 的单个路径段不能超过 255 个 UTF-8 字节");
        assertThatThrownBy(() -> FileRules.objectKey("safe/" + prefix + "a", "a.txt", NOW))
            .isInstanceOf(FileValidationException.class);
        // 限制作用于单段，不把整个前缀误算成一个路径段。
        assertThat(FileRules.objectKey("中".repeat(43) + "/" + "文".repeat(43), "a.txt", NOW))
            .endsWith(".txt");
    }

    @Test
    void retainsTotalUtf8BudgetEvenWhenEverySegmentFits() {
        String prefix = String.join("/", Collections.nCopies(4, "中".repeat(80))) + "/" + "a".repeat(7);
        String accepted = FileRules.objectKey(prefix, "a.txt", NOW);
        assertThat(accepted.getBytes(StandardCharsets.UTF_8)).hasSize(1023);
        assertThat(accepted.length()).isLessThanOrEqualTo(512);
        assertThatThrownBy(() -> FileRules.objectKey(prefix + "a", "a.txt", NOW))
            .isInstanceOf(FileValidationException.class)
            .hasMessage("对象 Key 的 UTF-8 长度不能超过 1023 字节");
    }

    @Test
    void keyUsesUtcDateRandomUuidAndOnlyLowercaseBasenameExtension() {
        String key = FileRules.objectKey("files", "../a.TXT", NOW);
        assertThat(key).matches("files/2026/10/08/[0-9a-f-]{36}\\.txt").doesNotContain("..");
        assertThat(FileRules.objectKey("files", "C:\\docs\\manual.DOCX", NOW)).endsWith(".docx");
        assertThat(FileRules.objectKey("files", "../a.TXT", NOW)).isNotEqualTo(key);
    }

    @Test
    void retainsExistingCharacterLimitEvenWhenEveryAsciiSegmentFits() {
        String prefix = "a".repeat(230) + "/" + "b".repeat(229);
        assertThat(FileRules.objectKey(prefix, "a.txt", NOW)).hasSize(512);
        assertThatThrownBy(() -> FileRules.objectKey(prefix + "b", "a.txt", NOW))
            .isInstanceOf(FileValidationException.class).hasMessage("对象 Key 超过长度限制");
    }
}
