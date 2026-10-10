package com.item.aisummary.domain.file.rule;

import com.item.aisummary.domain.file.exception.FileValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 新上传 Key 的共同字节预算，兼顾原字符上限和多字节名称。 */
class FileRulesTest {
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    void keyMustFitTheUtf8BudgetAsWellAsTheExistingLengthLimit() {
        String prefix = "中".repeat(150);
        String acceptedName = "文".repeat(173) + "a.txt";
        String rejectedName = "文".repeat(173) + "ab.txt";

        String accepted = FileRules.objectKey(prefix, acceptedName, NOW);
        assertThat(accepted.getBytes(StandardCharsets.UTF_8)).hasSize(1023);
        assertThat(accepted.length()).isLessThanOrEqualTo(512);
        assertThatThrownBy(() -> FileRules.objectKey(prefix, rejectedName, NOW))
            .isInstanceOf(FileValidationException.class)
            .hasMessage("对象 Key 的 UTF-8 长度不能超过 1023 字节");
    }

    @Test
    void longChineseCombinationIsRejectedButDefaultPrefixAllowsTheSameOriginalName() {
        String name = "文".repeat(251) + ".txt";
        assertThatThrownBy(() -> FileRules.objectKey("中".repeat(150), name, NOW))
            .isInstanceOf(FileValidationException.class)
            .hasMessage("对象 Key 的 UTF-8 长度不能超过 1023 字节");

        String key = FileRules.objectKey("files", name, NOW);
        assertThat(key).startsWith("files/2026/10/08/").endsWith("-" + name);
        assertThat(key.getBytes(StandardCharsets.UTF_8)).hasSize(811);
    }

    @Test
    void ordinaryNamesKeepUtcDateUuidAndSanitizedBasename() {
        String key = FileRules.objectKey("files", "../a.TXT", NOW);
        assertThat(key).matches("files/2026/10/08/[0-9a-f-]{36}-a\\.TXT").doesNotContain("..");
        assertThat(FileRules.objectKey("files", "../a.TXT", NOW)).isNotEqualTo(key);
    }

    @Test
    void retainsExistingCharacterLimitEvenForAsciiOnlyKeys() {
        assertThatThrownBy(() -> FileRules.objectKey("a".repeat(150), "b".repeat(314), NOW))
            .isInstanceOf(FileValidationException.class).hasMessage("对象 Key 超过长度限制");
    }
}
