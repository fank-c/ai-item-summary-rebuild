package com.item.aisummary.application.knowledge.indexing.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.item.aisummary.domain.knowledge.value.Heading;
import com.item.aisummary.domain.knowledge.value.SourceLocation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 文档格式解析相关数据合同（ParsedDocument、ContentBlock）单元测试。
 *
 * <p>验证阅读顺序从 0 连续性、不可修改列表防御、未识别位置保持 null 以及 toString 日志脱敏。
 */
class KnowledgeParseContractsTest {
    @Test
    void preservesContentAndOptionalMetadataWithoutInventingPositions() {
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

    @Test
    void rejectsInvalidParsedDocumentAndBlockStructure() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ParsedDocument(List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ParsedDocument(List.of(block(1))));
        assertThatIllegalArgumentException().isThrownBy(() -> new ParsedDocument(List.of(block(0), block(0))));
        assertThatIllegalArgumentException().isThrownBy(() -> new ContentBlock(-1, ContentBlockType.TEXT, "正文", null, null, false));
        assertThatIllegalArgumentException().isThrownBy(() -> new ContentBlock(0, null, "正文", null, null, false));
    }

    private static ContentBlock block(int ordinal) {
        return new ContentBlock(ordinal, ContentBlockType.TEXT, "正文", null, null, false);
    }
}
