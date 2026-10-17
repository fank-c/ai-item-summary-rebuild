package com.item.aisummary.infrastructure.knowledge.indexing.parse.pdf;

import static com.item.aisummary.infrastructure.knowledge.indexing.parse.support.ParserTestSources.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.item.aisummary.application.knowledge.indexing.parse.ContentBlockType;
import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.pdf.DocMindPdfParser.Layout;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.pdf.DocMindPdfParser.Result;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 阿里云文档智能（DocMind）PDF 解析策略单元测试。
 *
 * <p>基于隔离的 DocMindPdfParser 替身网关，测试覆盖：
 * <ul>
 *   <li>本地 PDFBox 容器防线：拦截损坏文件、加密 PDF、超页数限制（>2000 页）或 0 字节文件，且不向云端网关发起调用；</li>
 *   <li>阅读顺序与排序保障：按物理页码、页内 {@code layout.index} 排序，允许跨页序号重置并拒绝同页重复或空版面；</li>
 *   <li>页面覆盖与防丢页：核验 0 基或 1 基页面清单必须全覆盖本地实际页数，缺页时明确拒绝；</li>
 *   <li>标题与大纲降级：标题版面映射为 HEADING 块，headingPath 置空，跨页或标题触发硬边界；</li>
 *   <li>特殊排版提取：表格 HTML/Markdown 优先保留，图示说明提取，空版面拒绝；</li>
 *   <li>位置与偏移：计算拼接全文的 UTF-16 半开区间，0 基页码正确映射为 1 基物理页码。</li>
 * </ul>
 */
class AlibabaPdfKnowledgeDocumentParserTest {
    /** 捕获本地预检和云端结果校验日志，避免仅验证返回异常而遗漏排查信息。 */
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AlibabaPdfKnowledgeDocumentParser.class);

    @BeforeEach void attachLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach void detachLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void parsesScannedOrDigitalPdfThroughGatewayAndMapsPageOrderAndOffsets() throws Exception {
        byte[] bytes = pdfWithPages(2);
        DocMindPdfParser gateway = mock(DocMindPdfParser.class);
        when(gateway.parse(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new Result(List.of(
                        layout("第一章", "title", "doc_title", 0, 0, 0),
                        layout("扫描识别正文", "text", "para", 0, 0, 1),
                        layout("第二页正文", "text", "para", 0, 1, 2)), java.util.Set.of(0, 1)));

        var parser = new AlibabaPdfKnowledgeDocumentParser(1024 * 1024, gateway);
        var parsed = parser.parse(source(bytes, FileFormat.PDF));

        assertThat(parsed.blocks()).hasSize(3);
        assertThat(parsed.blocks()).extracting(block -> block.type()).containsExactly(
                ContentBlockType.HEADING, ContentBlockType.TEXT, ContentBlockType.TEXT);
        assertThat(parsed.blocks()).allSatisfy(block -> assertThat(block.headingPath()).isNull());
        assertThat(parsed.blocks().get(0).source().pageStart()).isEqualTo(1);
        assertThat(parsed.blocks().get(2).source().pageStart()).isEqualTo(2);
        assertThat(parsed.blocks().get(2).source().startOffset()).isEqualTo("第一章\n\n扫描识别正文\n\n".length());
        assertThat(parsed.blocks().get(2).boundaryBefore()).isTrue();
    }

    @Test
    void keepsTableAndFigureDescriptionsRatherThanDroppingRecognizedContent() throws Exception {
        var parsed = AlibabaPdfKnowledgeDocumentParser.toParsedDocument(List.of(
                new Layout("数据", "<table><tr><td>A</td><td>B</td></tr></table>", null,
                        "table", null, 0, 0, 0),
                new Layout("", null, "图示说明", "figure", null, 0, 0, 1)), 1);

        assertThat(parsed.blocks()).extracting(block -> block.type())
                .containsExactly(ContentBlockType.TABLE, ContentBlockType.TEXT);
        assertThat(parsed.blocks().get(0).text()).isEqualTo("<table><tr><td>A</td><td>B</td></tr></table>");
        assertThat(parsed.blocks().get(1).text()).isEqualTo("图示说明");
    }

    @Test
    void acceptsCompleteZeroOrOneBasedImageIdsWithoutChangingZeroBasedLayoutPages() throws Exception {
        byte[] bytes = pdfWithPages(2);
        DocMindPdfParser gateway = mock(DocMindPdfParser.class);
        var parser = new AlibabaPdfKnowledgeDocumentParser(1024 * 1024, gateway);
        var layouts = List.of(layout("第一页", "text", "para", null, 0, 0),
                layout("第二页", "text", "para", null, 1, 1));
        for (var pageIds : List.of(java.util.Set.of(0, 1), java.util.Set.of(1, 2))) {
            when(gateway.parse(org.mockito.ArgumentMatchers.any())).thenReturn(new Result(layouts, pageIds));
            assertThat(parser.parse(source(bytes, FileFormat.PDF)).blocks())
                    .extracting(block -> block.source().pageStart()).containsExactly(1, 2);
        }
        when(gateway.parse(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new Result(layouts, java.util.Set.of(0, 2)));
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(source(bytes, FileFormat.PDF)))
                .withMessage("PDF 解析结果未覆盖全部页面");
    }

    @Test
    void rejectsIncompletePageCoverageAndDuplicateReadingOrder() throws Exception {
        byte[] bytes = pdfWithPages(2);
        DocMindPdfParser gateway = mock(DocMindPdfParser.class);
        when(gateway.parse(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new Result(List.of(layout("仅第一页", "text", "para", 0, 0, 0)), java.util.Set.of(0)));
        var parser = new AlibabaPdfKnowledgeDocumentParser(1024 * 1024, gateway);
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(source(bytes, FileFormat.PDF)))
                .withMessage("PDF 解析结果未覆盖全部页面");
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains("stage=PAGE_COVERAGE", "pages=2", "processedPages=1"))
                .allSatisfy(message -> assertThat(message).doesNotContain("仅第一页"));
        assertThatIllegalArgumentException().isThrownBy(() -> AlibabaPdfKnowledgeDocumentParser.toParsedDocument(
                List.of(layout("正文", "text", "para", 0, 0, 0),
                        layout("重复序号", "text", "para", 0, 0, 0)), 1))
                .withMessage("PDF 解析结果阅读顺序无效");
        assertThatIllegalArgumentException().isThrownBy(() -> AlibabaPdfKnowledgeDocumentParser.toParsedDocument(
                List.of(layout("缺失序号", "text", "para", 0, 0, null)), 1))
                .withMessage("PDF 解析结果阅读顺序无效");
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains(
                        "missingIndexCount=1", "negativeIndexCount=0", "duplicateIndexCount=0",
                        "invalidIndexSamples=[0:missing]"))
                .allSatisfy(message -> assertThat(message).doesNotContain("缺失序号"));
    }

    @Test
    void ordersLayoutsByPageThenPageLocalIndexWhenProviderResetsIndexAcrossPages() {
        var parsed = AlibabaPdfKnowledgeDocumentParser.toParsedDocument(List.of(
                layout("第二页后块", "text", "para", null, 1, 1),
                layout("第一页后块", "text", "para", null, 0, 1),
                layout("第二页首块", "text", "para", null, 1, 0),
                layout("第一页首块", "text", "para", null, 0, 0)), 2);

        assertThat(parsed.blocks()).extracting(block -> block.text())
                .containsExactly("第一页首块", "第一页后块", "第二页首块", "第二页后块");
        assertThat(parsed.blocks()).extracting(block -> block.source().pageStart())
                .containsExactly(1, 1, 2, 2);
        assertThat(parsed.blocks()).extracting(block -> block.source().startOffset())
                .containsExactly(0, "第一页首块\n\n".length(), "第一页首块\n\n第一页后块\n\n".length(),
                        "第一页首块\n\n第一页后块\n\n第二页首块\n\n".length());
        assertThat(parsed.blocks().get(2).boundaryBefore()).isTrue();
    }

    @Test
    void rejectsDuplicatePageLocalIndexesEvenWhenPagesAreValid() {
        assertThatIllegalArgumentException().isThrownBy(() -> AlibabaPdfKnowledgeDocumentParser.toParsedDocument(
                List.of(layout("同页首块", "text", "para", null, 0, 2),
                        layout("同页重复块", "text", "para", null, 0, 2)), 1))
                .withMessage("PDF 解析结果阅读顺序无效");
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains(
                        "missingIndexCount=0", "negativeIndexCount=0", "duplicateIndexCount=1",
                        "invalidIndexSamples=[0:2]"))
                .allSatisfy(message -> assertThat(message).doesNotContain("同页首块", "同页重复块"));
    }

    @Test
    void rejectsBadPageNumbersAndEmptyLayoutsInsteadOfSilentlySkippingContent() {
        assertThatIllegalArgumentException().isThrownBy(() -> AlibabaPdfKnowledgeDocumentParser.toParsedDocument(
                List.of(layout("正文", "text", "para", 0, 1, 0)), 1))
                .withMessage("PDF 解析结果页码无效");
        assertThatIllegalArgumentException().isThrownBy(() -> AlibabaPdfKnowledgeDocumentParser.toParsedDocument(
                List.of(layout(" ", "text", "para", 0, 0, 0)), 1))
                .withMessage("PDF 解析结果包含无法表示的空版面");
    }

    @Test
    void validatesPdfContainerBeforeCallingCloudGateway() {
        DocMindPdfParser gateway = mock(DocMindPdfParser.class);
        var parser = new AlibabaPdfKnowledgeDocumentParser(1024, gateway);

        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(source("not pdf", FileFormat.PDF)))
                .withMessage("PDF 格式无效或已损坏");
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains("stage=PDFBOX_VALIDATE", "java.io.IOException", "failureLocation="))
                .allSatisfy(message -> assertThat(message).doesNotContain("not pdf"));
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        verifyNoInteractions(gateway);
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(source("text", FileFormat.TXT)))
                .withMessage("PDF 源文件无效或超过解析限制");
    }

    @Test
    void rejectsPdfOverTheConfiguredSourceBudgetBeforeCallingCloud() throws Exception {
        DocMindPdfParser gateway = mock(DocMindPdfParser.class);
        var bytes = pdfWithPages(1);
        var parser = new AlibabaPdfKnowledgeDocumentParser(bytes.length - 1L, gateway);

        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(source(bytes, FileFormat.PDF)))
                .withMessage("PDF 源文件无效或超过解析限制");
        verifyNoInteractions(gateway);
    }

    @Test
    void missingHeadingLevelDoesNotInventHierarchyAndLayoutOrderMayHaveGaps() {
        var parsed = AlibabaPdfKnowledgeDocumentParser.toParsedDocument(List.of(
                layout("正文", "text", "para", 8, 0, 10),
                layout("标题", "title", "para_title", null, 0, 2)), 1);
        assertThat(parsed.blocks()).extracting(block -> block.text()).containsExactly("标题", "正文");
        assertThat(parsed.blocks()).allSatisfy(block -> assertThat(block.headingPath()).isNull());
    }

    @Test
    void unrecognizedImagesAndFormulasFailInsteadOfInventingContent() {
        for (String type : List.of("figure", "formula", "unknown")) {
            assertThatIllegalArgumentException().isThrownBy(() -> AlibabaPdfKnowledgeDocumentParser.toParsedDocument(
                    List.of(layout("", type, null, null, 0, 0)), 1))
                    .withMessage("PDF 解析结果包含无法表示的空版面");
        }
    }

    @Test
    void rejectsPasswordProtectedPdfBeforeCloudUpload() throws Exception {
        byte[] bytes;
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                    "fixture-owner", "fixture-user", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            document.save(output);
            bytes = output.toByteArray();
        }
        DocMindPdfParser gateway = mock(DocMindPdfParser.class);
        var parser = new AlibabaPdfKnowledgeDocumentParser(1024 * 1024, gateway);
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(source(bytes, FileFormat.PDF)))
                .withMessage("不支持加密 PDF");
        verifyNoInteractions(gateway);
    }

    private static Layout layout(String text, String type, String subtype, Integer level, Integer page, Integer index) {
        return new Layout(text, null, null, type, subtype, level, page, index);
    }

    private static byte[] pdfWithPages(int count) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int i = 0; i < count; i++) document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }
}
