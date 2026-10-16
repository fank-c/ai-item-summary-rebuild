package com.item.aisummary.infrastructure.knowledge.indexing.parse.markdown;

import static com.item.aisummary.infrastructure.knowledge.indexing.parse.support.ParserTestSources.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.item.aisummary.application.knowledge.indexing.parse.ContentBlock;
import com.item.aisummary.application.knowledge.indexing.parse.ContentBlockType;
import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.domain.knowledge.value.Heading;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownKnowledgeDocumentParserTest {
    private final MarkdownKnowledgeDocumentParser parser = new MarkdownKnowledgeDocumentParser(20 * 1024 * 1024);

    @Test
    void preservesMarkdownAndTracksTopLevelHeadingHierarchyWithoutInventingLevels() {
        String text = "前言\n\n# 安装 **指南**\n正文含 `code`、**强调** 和 [链接](https://example.invalid)。\n\n"
                + "### 数据库\n配置。\n\n## 存储\n内容。\n\n# 结束\n尾段。";
        var blocks = parse(text);
        assertThat(blocks).extracting(ContentBlock::type).containsExactly(
                ContentBlockType.TEXT, ContentBlockType.HEADING, ContentBlockType.TEXT,
                ContentBlockType.HEADING, ContentBlockType.TEXT, ContentBlockType.HEADING,
                ContentBlockType.TEXT, ContentBlockType.HEADING, ContentBlockType.TEXT);
        assertThat(blocks.getFirst().headingPath()).isEmpty();
        assertThat(blocks.get(1).text()).isEqualTo("# 安装 **指南**");
        assertThat(blocks.get(2).text()).isEqualTo("正文含 `code`、**强调** 和 [链接](https://example.invalid)。");
        assertThat(blocks.get(4).headingPath()).containsExactly(new Heading(1, "安装 **指南**"), new Heading(3, "数据库"));
        assertThat(blocks.get(6).headingPath()).containsExactly(new Heading(1, "安装 **指南**"), new Heading(2, "存储"));
        assertThat(blocks.get(8).headingPath()).containsExactly(new Heading(1, "结束"));
        assertThat(blocks).filteredOn(block -> block.type() == ContentBlockType.HEADING)
                .allMatch(ContentBlock::boundaryBefore);
        assertLocations(text, blocks);
    }

    @Test
    void distinguishesSetextHeadingFromThematicBreakAndCarriesBoundaryAcrossRepeatedBreaks() {
        String text = "安装说明\n---\n\n第一段。\n\n***\n\n---\n\n第二段。";
        var blocks = parse(text);
        assertThat(blocks).extracting(ContentBlock::text).containsExactly("安装说明\n---", "第一段。", "第二段。");
        assertThat(blocks.getFirst().type()).isEqualTo(ContentBlockType.HEADING);
        assertThat(blocks.getFirst().headingPath()).containsExactly(new Heading(2, "安装说明"));
        assertThat(blocks.get(1).boundaryBefore()).isFalse();
        assertThat(blocks.get(2).boundaryBefore()).isTrue();
        assertLocations(text, blocks);
    }

    @Test
    void keepsCodeFencesLanguageIndentationAndInternalMarkdownLiteral() {
        String fence = "```yaml\n# not a heading\nserver:\n  port: 8080\n---\n```";
        String indented = "    # also code\n    x = 1";
        String text = "## 配置\n\n" + fence + "\n\n" + indented;
        var blocks = parse(text);
        assertThat(blocks).extracting(ContentBlock::type).containsExactly(
                ContentBlockType.HEADING, ContentBlockType.CODE, ContentBlockType.CODE);
        assertThat(blocks.get(1).text()).isEqualTo(fence);
        assertThat(blocks.get(2).text()).isEqualTo(indented);
        assertThat(blocks.get(1).headingPath()).containsExactly(new Heading(2, "配置"));
        assertThat(blocks.get(1).boundaryBefore()).isFalse();
        assertLocations(text, blocks);
    }

    @Test
    void preservesTablesIncludingSeparatorRowsAndEscapedPipes() {
        String table = "| 配置项 | 默认值 |\n| :--- | ---: |\n| port | 8080 |\n| text | a\\|b |";
        var blocks = parse(table);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst().type()).isEqualTo(ContentBlockType.TABLE);
        assertThat(blocks.getFirst().text()).isEqualTo(table);
        assertLocations(table, blocks);
    }

    @Test
    void emitsContainersOnceAndDoesNotLetNestedHeadingsPolluteGlobalHeadingPath() {
        String list = "- 第一项\n  - 子项\n\n- 第二项";
        String quote = "> ## 引用标题\n>\n> 引用内容";
        String text = "# 外部标题\n\n" + list + "\n\n" + quote + "\n\n普通正文。";
        var blocks = parse(text);
        assertThat(blocks).extracting(ContentBlock::text).containsExactly("# 外部标题", list, quote, "普通正文。");
        assertThat(blocks.subList(1, 4)).allMatch(block -> block.type() == ContentBlockType.TEXT);
        assertThat(blocks.subList(1, 4)).allMatch(block -> block.headingPath().equals(List.of(new Heading(1, "外部标题"))));
        assertLocations(text, blocks);
    }

    @Test
    void keepsHtmlImagesMathAndLinkReferenceDefinitionsWithoutFetchingOrRendering() {
        String text = "[指南][deploy]\n\n[deploy]: https://example.invalid/deploy\n  \"部署\"\n\n"
                + "![图片](file:///no-such-image.png)\n\n<script>notExecuted()</script>\n\n$$\nE = mc^2\n$$";
        var blocks = parse(text);
        assertThat(blocks).extracting(ContentBlock::text).containsExactly(
                "[指南][deploy]", "[deploy]: https://example.invalid/deploy\n  \"部署\"",
                "![图片](file:///no-such-image.png)", "<script>notExecuted()</script>", "$$\nE = mc^2\n$$");
        assertThat(blocks).allMatch(block -> block.type() == ContentBlockType.TEXT);
        assertLocations(text, blocks);
    }

    @Test
    void acceptsUnclosedFencesAndPreservesEmptyHeadingsWithoutCreatingInvalidHeadingValues() {
        var blocks = parse("#\n\n正文\n\n~~~java\n## code to eof");
        assertThat(blocks).extracting(ContentBlock::text).containsExactly("#", "正文", "~~~java\n## code to eof");
        assertThat(blocks.getFirst().type()).isEqualTo(ContentBlockType.TEXT);
        assertThat(blocks.getFirst().boundaryBefore()).isTrue();
        assertThat(blocks.getFirst().headingPath()).isEmpty();
        assertThat(blocks.getLast().type()).isEqualTo(ContentBlockType.CODE);
    }

    @Test
    void measuresOffsetsAfterBomRemovalAndLineNormalizationInUtf16() {
        String text = "# 🚀 标题\n\n\t代码\n\n段落😀  ";
        var blocks = parser.parse(source("\ufeff" + text.replace("\n", "\r\n"), FileFormat.MD)).blocks();
        assertThat(blocks).extracting(ContentBlock::text).containsExactly("# 🚀 标题", "\t代码", "段落😀  ");
        assertLocations(text, blocks);
        assertThat(parser.parse(source(text, FileFormat.MD)).blocks()).isEqualTo(blocks);
    }

    @Test
    void preservesTabsInsideNestedContainerWithoutUsingChildSpanLengths() {
        String text = "- item\n\tx\n\t😀";
        var blocks = parse(text);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst().text()).isEqualTo(text);
        assertLocations(text, blocks);
    }

    @Test
    void rejectsEmptyOrSeparatorOnlyDocumentsAndWrongFormatWithSafeMessages() {
        for (String text : List.of("", " \n\t\n", "---\n\n***\n\n___")) {
            assertThatIllegalArgumentException().isThrownBy(() -> parse(text))
                    .withMessage("文档没有可解析的有效内容");
        }
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(source("private body", FileFormat.TXT)))
                .withMessageNotContaining("private");
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(null));
        assertThatIllegalArgumentException().isThrownBy(() -> new MarkdownKnowledgeDocumentParser(0));
        assertThatIllegalArgumentException().isThrownBy(() -> new MarkdownKnowledgeDocumentParser(Integer.MAX_VALUE));
    }

    @Test
    void rejectsMalformedUtf8AndSourceBudgetOverflow() {
        assertThatThrownBy(() -> parser.parse(source(new byte[]{(byte) 0xc3}, FileFormat.MD)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("源文件 UTF-8 解码失败");
        assertThatIllegalArgumentException().isThrownBy(() -> new MarkdownKnowledgeDocumentParser(3)
                .parse(source("四字节", FileFormat.MD)));
    }

    @Test
    void limitsDeepContainerNestingBeforeBuildingUnboundedAst() {
        assertThatIllegalArgumentException().isThrownBy(() -> parse("> ".repeat(200) + "body"))
                .withMessage("Markdown 嵌套层级超过解析限制");
    }

    @Test
    void limitsLineAndOutputBlockCounts() {
        assertThatIllegalArgumentException().isThrownBy(() -> parse("\n".repeat(1_000_001)))
                .withMessage("Markdown 行数超过解析限制");
        assertThatIllegalArgumentException().isThrownBy(() -> parse("a\n\n".repeat(100_001)))
                .withMessage("文档内容块数量超过解析限制");
    }

    private List<ContentBlock> parse(String text) {
        return parser.parse(source(text, FileFormat.MD)).blocks();
    }

    private static void assertLocations(String text, List<ContentBlock> blocks) {
        int previousEnd = 0;
        for (int index = 0; index < blocks.size(); index++) {
            var block = blocks.get(index);
            assertThat(block.ordinal()).isEqualTo(index);
            assertThat(block.source()).isNotNull();
            assertThat(block.source().pageStart()).isNull();
            assertThat(block.source().pageEnd()).isNull();
            assertThat(block.source().startOffset()).isGreaterThanOrEqualTo(previousEnd);
            assertThat(text.substring(block.source().startOffset(), block.source().endOffset())).isEqualTo(block.text());
            previousEnd = block.source().endOffset();
        }
    }
}
