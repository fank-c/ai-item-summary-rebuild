package com.item.aisummary.infrastructure.knowledge.indexing.parse.txt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.item.aisummary.application.knowledge.indexing.parse.ContentBlock;
import com.item.aisummary.application.knowledge.indexing.parse.ContentBlockType;
import com.item.aisummary.application.knowledge.indexing.parse.ParsedDocument;
import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.application.knowledge.indexing.source.PreparedSource;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.support.ParserTestSources;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.support.Utf8TextDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TextKnowledgeDocumentParserTest {

    @Test
    void declaresTxtFormat() {
        var parser = new TextKnowledgeDocumentParser(1024);
        assertThat(parser.format()).isEqualTo(FileFormat.TXT);
    }

    @Test
    void validatesConstructorBudgetBoundaries() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TextKnowledgeDocumentParser(0))
                .withMessage("源文件大小上限必须为正且小于 Integer.MAX_VALUE 字节");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TextKnowledgeDocumentParser(-10))
                .withMessage("源文件大小上限必须为正且小于 Integer.MAX_VALUE 字节");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TextKnowledgeDocumentParser(Integer.MAX_VALUE))
                .withMessage("源文件大小上限必须为正且小于 Integer.MAX_VALUE 字节");
    }

    @Test
    void parsesTextParagraphsSeparatedByBlankLines() {
        var parser = new TextKnowledgeDocumentParser(1024);
        String text = "第一段文本内容。\n\n第二段文本内容。";
        PreparedSource source = ParserTestSources.source(text, FileFormat.TXT);

        ParsedDocument parsed = parser.parse(source);

        assertThat(parsed.blocks()).hasSize(2);

        ContentBlock b0 = parsed.blocks().get(0);
        assertThat(b0.ordinal()).isEqualTo(0);
        assertThat(b0.type()).isEqualTo(ContentBlockType.TEXT);
        assertThat(b0.text()).isEqualTo("第一段文本内容。");
        assertThat(b0.headingPath()).isNull();
        assertThat(b0.boundaryBefore()).isFalse();
        assertThat(b0.source().startOffset()).isEqualTo(0);
        assertThat(b0.source().endOffset()).isEqualTo(8);
        assertThat(b0.source().pageStart()).isNull();
        assertThat(b0.source().pageEnd()).isNull();
        assertThat(text.substring(b0.source().startOffset(), b0.source().endOffset()))
                .isEqualTo(b0.text());

        ContentBlock b1 = parsed.blocks().get(1);
        assertThat(b1.ordinal()).isEqualTo(1);
        assertThat(b1.type()).isEqualTo(ContentBlockType.TEXT);
        assertThat(b1.text()).isEqualTo("第二段文本内容。");
        assertThat(b1.headingPath()).isNull();
        assertThat(b1.boundaryBefore()).isFalse();
        assertThat(b1.source().startOffset()).isEqualTo(10);
        assertThat(b1.source().endOffset()).isEqualTo(18);
        assertThat(text.substring(b1.source().startOffset(), b1.source().endOffset()))
                .isEqualTo(b1.text());

        // toString 保护脱敏，不泄漏全文
        assertThat(parsed.toString()).doesNotContain("第一段", "第二段");
        assertThat(b0.toString()).doesNotContain("第一段");
    }

    @Test
    void preservesSingleNewlineWithinParagraph() {
        var parser = new TextKnowledgeDocumentParser(1024);
        String text = "段落一行一\n段落一行二\n\n段落二行一";
        PreparedSource source = ParserTestSources.source(text, FileFormat.TXT);

        ParsedDocument parsed = parser.parse(source);

        assertThat(parsed.blocks()).hasSize(2);
        ContentBlock b0 = parsed.blocks().get(0);
        assertThat(b0.text()).isEqualTo("段落一行一\n段落一行二");
        assertThat(b0.source().startOffset()).isEqualTo(0);
        assertThat(b0.source().endOffset()).isEqualTo(11);
        assertThat(text.substring(b0.source().startOffset(), b0.source().endOffset()))
                .isEqualTo(b0.text());

        ContentBlock b1 = parsed.blocks().get(1);
        assertThat(b1.text()).isEqualTo("段落二行一");
        assertThat(b1.source().startOffset()).isEqualTo(13);
        assertThat(b1.source().endOffset()).isEqualTo(18);
        assertThat(text.substring(b1.source().startOffset(), b1.source().endOffset()))
                .isEqualTo(b1.text());
    }

    @Test
    void preservesIndentationAndTrailingSpaces() {
        var parser = new TextKnowledgeDocumentParser(1024);
        String text = "  缩进正文一行  \n  缩进正文二行  \n\n末尾带空格段落   ";
        PreparedSource source = ParserTestSources.source(text, FileFormat.TXT);

        ParsedDocument parsed = parser.parse(source);

        assertThat(parsed.blocks()).hasSize(2);
        ContentBlock b0 = parsed.blocks().get(0);
        assertThat(b0.text()).isEqualTo("  缩进正文一行  \n  缩进正文二行  ");
        assertThat(text.substring(b0.source().startOffset(), b0.source().endOffset()))
                .isEqualTo(b0.text());

        ContentBlock b1 = parsed.blocks().get(1);
        assertThat(b1.text()).isEqualTo("末尾带空格段落   ");
        assertThat(text.substring(b1.source().startOffset(), b1.source().endOffset()))
                .isEqualTo(b1.text());
    }

    @Test
    void handlesBlankLinesWithVariousWhitespaceAndNbsp() {
        var parser = new TextKnowledgeDocumentParser(1024);
        // 空行包含普通空格、制表符以及不间断空格 NBSP (\u00A0)
        String text = "第一段\n   \n\t\t\n\u00A0\u00A0\n第二段";
        PreparedSource source = ParserTestSources.source(text, FileFormat.TXT);

        ParsedDocument parsed = parser.parse(source);

        assertThat(parsed.blocks()).hasSize(2);
        assertThat(parsed.blocks().get(0).text()).isEqualTo("第一段");
        assertThat(parsed.blocks().get(1).text()).isEqualTo("第二段");
    }

    @Test
    void handlesLeadingAndTrailingBlankLines() {
        var parser = new TextKnowledgeDocumentParser(1024);
        String text = "\n\n   \n\u00A0\n唯一的一段正文\n\n   \n\n";
        PreparedSource source = ParserTestSources.source(text, FileFormat.TXT);

        ParsedDocument parsed = parser.parse(source);

        assertThat(parsed.blocks()).hasSize(1);
        ContentBlock block = parsed.blocks().get(0);
        assertThat(block.text()).isEqualTo("唯一的一段正文");
        assertThat(text.substring(block.source().startOffset(), block.source().endOffset()))
                .isEqualTo("唯一的一段正文");
    }

    @Test
    void handlesMixedNewlinesCrlfCrLfAndMaintainsNormalizedOffsets() {
        var parser = new TextKnowledgeDocumentParser(1024);
        String raw = "段落一行一\r\n段落一行二\r\n\r\n段落二行一\r段落二行二\r\r段落三\n段落三行二";
        PreparedSource source = ParserTestSources.source(raw, FileFormat.TXT);

        ParsedDocument parsed = parser.parse(source);

        // 规范化后的全文所有换行都统一为 \n
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');

        assertThat(parsed.blocks()).hasSize(3);

        ContentBlock b0 = parsed.blocks().get(0);
        assertThat(b0.text()).isEqualTo("段落一行一\n段落一行二");
        assertThat(normalized.substring(b0.source().startOffset(), b0.source().endOffset()))
                .isEqualTo(b0.text());

        ContentBlock b1 = parsed.blocks().get(1);
        assertThat(b1.text()).isEqualTo("段落二行一\n段落二行二");
        assertThat(normalized.substring(b1.source().startOffset(), b1.source().endOffset()))
                .isEqualTo(b1.text());

        ContentBlock b2 = parsed.blocks().get(2);
        assertThat(b2.text()).isEqualTo("段落三\n段落三行二");
        assertThat(normalized.substring(b2.source().startOffset(), b2.source().endOffset()))
                .isEqualTo(b2.text());
    }

    @Test
    void doesNotGuessHeadingOrCode() {
        var parser = new TextKnowledgeDocumentParser(2048);
        String text = "# 一级标题\n\n## 二级标题\n\n```java\nint x = 1;\n```\n\n    缩进4空格代码";
        PreparedSource source = ParserTestSources.source(text, FileFormat.TXT);

        ParsedDocument parsed = parser.parse(source);

        assertThat(parsed.blocks()).hasSize(4);
        for (ContentBlock block : parsed.blocks()) {
            assertThat(block.type()).isEqualTo(ContentBlockType.TEXT);
            assertThat(block.headingPath()).isNull();
            assertThat(block.boundaryBefore()).isFalse();
        }
        assertThat(parsed.blocks().get(0).text()).isEqualTo("# 一级标题");
        assertThat(parsed.blocks().get(1).text()).isEqualTo("## 二级标题");
        assertThat(parsed.blocks().get(2).text()).isEqualTo("```java\nint x = 1;\n```");
        assertThat(parsed.blocks().get(3).text()).isEqualTo("    缩进4空格代码");
    }

    @Test
    void correctlyCalculatesSurrogateOffsets() {
        var parser = new TextKnowledgeDocumentParser(2048);
        // Emoji 表情（😀 \uD83D\uDE00, 🚀 \uD83D\uDE80）在 UTF-16 中各占 2 个 char
        String text = "表情 😀 前后文字\n\n下一行 🚀 火箭段落";
        PreparedSource source = ParserTestSources.source(text, FileFormat.TXT);

        ParsedDocument parsed = parser.parse(source);

        assertThat(parsed.blocks()).hasSize(2);
        ContentBlock b0 = parsed.blocks().get(0);
        assertThat(b0.text()).isEqualTo("表情 😀 前后文字");
        assertThat(text.substring(b0.source().startOffset(), b0.source().endOffset()))
                .isEqualTo(b0.text());

        ContentBlock b1 = parsed.blocks().get(1);
        assertThat(b1.text()).isEqualTo("下一行 🚀 火箭段落");
        assertThat(text.substring(b1.source().startOffset(), b1.source().endOffset()))
                .isEqualTo(b1.text());
    }

    @Test
    void stripsSingleUtf8BomAtBeginning() {
        var parser = new TextKnowledgeDocumentParser(1024);
        String textWithBom = "\uFEFF正文内容第一段\n\n第二段内容";
        PreparedSource source = ParserTestSources.source(textWithBom, FileFormat.TXT);

        ParsedDocument parsed = parser.parse(source);

        String normalizedWithoutBom = "正文内容第一段\n\n第二段内容";
        assertThat(parsed.blocks()).hasSize(2);

        ContentBlock b0 = parsed.blocks().get(0);
        assertThat(b0.text()).isEqualTo("正文内容第一段");
        assertThat(b0.text()).doesNotStartWith("\uFEFF");
        assertThat(normalizedWithoutBom.substring(b0.source().startOffset(), b0.source().endOffset()))
                .isEqualTo(b0.text());

        ContentBlock b1 = parsed.blocks().get(1);
        assertThat(b1.text()).isEqualTo("第二段内容");
        assertThat(normalizedWithoutBom.substring(b1.source().startOffset(), b1.source().endOffset()))
                .isEqualTo(b1.text());
    }

    @Test
    void rejectsBlankOrEmptySource() {
        var parser = new TextKnowledgeDocumentParser(1024);

        // 0 字节空源
        PreparedSource emptySource = ParserTestSources.source(new byte[0], FileFormat.TXT);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(emptySource))
                .withMessage("文档内容不能为空白");

        // 纯空白字符（换行、空格、制表符）
        PreparedSource whitespaceSource = ParserTestSources.source("   \n\t  \n\n   ", FileFormat.TXT);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(whitespaceSource))
                .withMessage("文档内容不能为空白");

        // 仅包含 NBSP 和换行
        PreparedSource nbspSource = ParserTestSources.source("\u00A0\u00A0\n\u00A0", FileFormat.TXT);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(nbspSource))
                .withMessage("文档内容不能为空白");
    }

    @Test
    void rejectsSourceFormatMismatchAndNullSource() {
        var parser = new TextKnowledgeDocumentParser(1024);

        PreparedSource mdSource = ParserTestSources.source("内容", FileFormat.MD);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(mdSource))
                .withMessage("源文件格式不匹配");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(null))
                .withMessage("源文件对象不能为空");
    }

    @Test
    void rejectsMalformedUtf8TruncatedGbkAndUtf16() {
        var parser = new TextKnowledgeDocumentParser(1024);

        // 非法截断
        byte[] truncated = new byte[]{'a', (byte) 0xE4, (byte) 0xB8};
        assertThatThrownBy(() -> parser.parse(ParserTestSources.source(truncated, FileFormat.TXT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("源文件 UTF-8 解码失败");

        // GBK
        byte[] gbk = "GBK中文".getBytes(Charset.forName("GBK"));
        assertThatThrownBy(() -> parser.parse(ParserTestSources.source(gbk, FileFormat.TXT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("源文件 UTF-8 解码失败");

        // UTF-16
        byte[] utf16 = "UTF-16文本".getBytes(StandardCharsets.UTF_16);
        assertThatThrownBy(() -> parser.parse(ParserTestSources.source(utf16, FileFormat.TXT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("源文件 UTF-8 解码失败");
    }

    @Test
    void enforcesMaxSourceSizeBudgetBoundary() {
        var parser = new TextKnowledgeDocumentParser(10);

        // 刚好 10 字节成功
        PreparedSource exactSource = ParserTestSources.source("0123456789", FileFormat.TXT);
        ParsedDocument doc = parser.parse(exactSource);
        assertThat(doc.blocks()).hasSize(1);
        assertThat(doc.blocks().get(0).text()).isEqualTo("0123456789");

        // 11 字节超过预算被拒绝
        PreparedSource exceededSource = ParserTestSources.source("0123456789a", FileFormat.TXT);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(exceededSource))
                .withMessage("源文件大小超过预算限制");
    }

    @Test
    void supportsRepeatParseOnSameSource() {
        var parser = new TextKnowledgeDocumentParser(1024);
        PreparedSource source = ParserTestSources.source("段落一\n\n段落二", FileFormat.TXT);

        ParsedDocument first = parser.parse(source);
        ParsedDocument second = parser.parse(source);

        assertThat(first.blocks()).hasSize(2);
        assertThat(second.blocks()).hasSize(2);
        assertThat(first.blocks().get(0).text()).isEqualTo(second.blocks().get(0).text());
        assertThat(first.blocks().get(1).text()).isEqualTo(second.blocks().get(1).text());
    }

    @Test
    void enforcesMaxBlocksBudget() {
        // 创建超过 MAX_BLOCKS (100,000) 个内容块的文本，例如 100,001 个块
        // 每个块为 "x\n\n"，共 300,003 字节
        var parser = new TextKnowledgeDocumentParser(1_000_000);
        int blockCount = Utf8TextDecoder.MAX_BLOCKS + 1;
        String bigText = "x\n\n".repeat(blockCount);
        PreparedSource source = ParserTestSources.source(bigText, FileFormat.TXT);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(source))
                .withMessage("解析内容块数量超过最大限制: 100000");
    }

    @Test
    void doesNotEchoSensitiveContentOrFileNameInExceptions() {
        var parser = new TextKnowledgeDocumentParser(1024);
        byte[] sensitiveBytes = "PASSWORD=secret123\uFFFD\uFFFF".getBytes(StandardCharsets.UTF_8);
        sensitiveBytes[sensitiveBytes.length - 1] = (byte) 0xFF; // 解码失败

        PreparedSource source = ParserTestSources.source(sensitiveBytes, FileFormat.TXT);

        assertThatThrownBy(() -> parser.parse(source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("源文件 UTF-8 解码失败")
                .hasMessageNotContaining("PASSWORD")
                .hasMessageNotContaining("secret123")
                .hasMessageNotContaining("test-doc.txt");
    }
}
