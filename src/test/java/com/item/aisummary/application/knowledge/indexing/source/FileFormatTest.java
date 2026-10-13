package com.item.aisummary.application.knowledge.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/**
 * 源文件格式枚举与扩展名路由规则单元测试。
 *
 * <p>验证末段扩展名大小写不敏感解析，以及对非法、缺失或不支持（如 .doc）扩展名的显式拒绝。
 */
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
