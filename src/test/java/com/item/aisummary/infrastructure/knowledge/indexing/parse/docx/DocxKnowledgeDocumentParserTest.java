package com.item.aisummary.infrastructure.knowledge.indexing.parse.docx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.item.aisummary.application.knowledge.indexing.parse.ContentBlock;
import com.item.aisummary.application.knowledge.indexing.parse.ContentBlockType;
import com.item.aisummary.application.knowledge.indexing.parse.ParsedDocument;
import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.application.knowledge.indexing.source.PreparedSource;
import com.item.aisummary.domain.knowledge.value.Heading;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.support.ParserTestSources;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDecimalNumber;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;

/**
 * Office Word（DOCX）文档解析策略单元测试。
 *
 * <p>测试覆盖范围：
 * <ul>
 *   <li>基础参数：大小预算有效性、格式声明与入参防御；</li>
 *   <li>前置安全预检（Preflight）：ZIP 格式损坏、ZipSlip 路径穿越、条目数量/大小超限、ZIP 压缩炸弹（压缩比超 100:1）、XML DTD/XXE 注入拦截、XML 嵌套深度超限（>128）；</li>
 *   <li>正文提取与排版顺序：顶层段落与表格交错阅读顺序保留、段落 Run 文本合并、换行规整（CRLF -> LF）、空单元格与多段落表格支持；</li>
 *   <li>标题大纲解析优先级：段落内联 outline 0..8 -> 1..9 映射、outline=9 显式正文阻断回退、样式表 basedOn 继承链回溯、中英文标题别名匹配、循环继承与样式深度（>32）拦截；</li>
 *   <li>不支持排版防御：数学公式（oMath）、文本框、图片（drawing/pict）、嵌套表格、不支持的正文容器（altChunk/sdt）严格拦截；</li>
 *   <li>位置与偏移：多块段落逻辑全文拼接后的 UTF-16 偏移半开区间 {@code [start_offset, end_offset)} 计算。</li>
 * </ul>
 */
class DocxKnowledgeDocumentParserTest {
    private final DocxKnowledgeDocumentParser parser = new DocxKnowledgeDocumentParser(20 * 1024 * 1024);

    @Test
    void declaresDocxFormat() {
        assertThat(parser.format()).isEqualTo(FileFormat.DOCX);
    }

    @Test
    void validatesConstructorBudgetBoundaries() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DocxKnowledgeDocumentParser(0))
                .withMessage("源文件大小上限必须为正且小于 Integer.MAX_VALUE 字节");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DocxKnowledgeDocumentParser(-1))
                .withMessage("源文件大小上限必须为正且小于 Integer.MAX_VALUE 字节");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DocxKnowledgeDocumentParser(Integer.MAX_VALUE))
                .withMessage("源文件大小上限必须为正且小于 Integer.MAX_VALUE 字节");
    }

    @Test
    void parsesInterleavedParagraphsAndTablesPreservingOrder() {
        PreparedSource source = DocxTestSamples.poiDocx(doc -> {
            // 0: Heading 1
            XWPFParagraph p0 = doc.createParagraph();
            p0.setStyle("Heading 1");
            p0.createRun().setText("第一章 概述");

            // 1: Text
            XWPFParagraph p1 = doc.createParagraph();
            p1.createRun().setText("这是第一段正文说明。");

            // 2: Table
            XWPFTable t0 = doc.createTable(2, 2);
            t0.getRow(0).getCell(0).setText("表头1");
            t0.getRow(0).getCell(1).setText("表头2");
            t0.getRow(1).getCell(0).setText("数据1");
            t0.getRow(1).getCell(1).setText("数据2");

            // 3: Text
            XWPFParagraph p2 = doc.createParagraph();
            p2.createRun().setText("表格中间的正文。");

            // 4: Table 2
            XWPFTable t1 = doc.createTable(1, 2);
            t1.getRow(0).getCell(0).setText("单行表A");
            t1.getRow(0).getCell(1).setText("单行表B");

            // 5: Text
            XWPFParagraph p3 = doc.createParagraph();
            p3.createRun().setText("尾部段落。");
        });

        ParsedDocument parsed = parser.parse(source);

        assertThat(parsed.blocks()).hasSize(6);
        assertThat(parsed.blocks()).extracting(ContentBlock::type).containsExactly(
                ContentBlockType.HEADING,
                ContentBlockType.TEXT,
                ContentBlockType.TABLE,
                ContentBlockType.TEXT,
                ContentBlockType.TABLE,
                ContentBlockType.TEXT
        );
        assertThat(parsed.blocks()).extracting(ContentBlock::ordinal).containsExactly(0, 1, 2, 3, 4, 5);

        // 验证正文提取
        assertThat(parsed.blocks().get(0).text()).isEqualTo("第一章 概述");
        assertThat(parsed.blocks().get(0).boundaryBefore()).isTrue();
        assertThat(parsed.blocks().get(0).headingPath()).containsExactly(new Heading(1, "第一章 概述"));

        assertThat(parsed.blocks().get(1).text()).isEqualTo("这是第一段正文说明。");
        assertThat(parsed.blocks().get(1).boundaryBefore()).isFalse();
        assertThat(parsed.blocks().get(1).headingPath()).containsExactly(new Heading(1, "第一章 概述"));

        assertThat(parsed.blocks().get(2).text()).isEqualTo("表头1\t表头2\n数据1\t数据2");
        assertThat(parsed.blocks().get(2).boundaryBefore()).isFalse();

        assertThat(parsed.blocks().get(3).text()).isEqualTo("表格中间的正文。");
        assertThat(parsed.blocks().get(4).text()).isEqualTo("单行表A\t单行表B");
        assertThat(parsed.blocks().get(5).text()).isEqualTo("尾部段落。");

        assertLogicalOffsets(parsed);
    }

    @Test
    void tracksHeadingHierarchyAndSnapshotsWithoutInventingLevels() {
        PreparedSource source = DocxTestSamples.poiDocx(doc -> {
            // 前言（无标题上下文）
            doc.createParagraph().createRun().setText("前言正文。");

            // H1
            XWPFParagraph h1 = doc.createParagraph();
            h1.setStyle("Heading 1");
            h1.createRun().setText("第 1 章 架构");

            // H1 下正文
            doc.createParagraph().createRun().setText("架构简介。");

            // H2
            XWPFParagraph h2 = doc.createParagraph();
            h2.setStyle("Heading 2");
            h2.createRun().setText("1.1 模块");

            // H2 下正文
            doc.createParagraph().createRun().setText("模块说明。");

            // H3
            XWPFParagraph h3 = doc.createParagraph();
            h3.setStyle("Heading 3");
            h3.createRun().setText("1.1.1 存储");

            // H3 下正文
            doc.createParagraph().createRun().setText("存储详情。");

            // 第二个 H2（回退，重置 H3）
            XWPFParagraph h2_2 = doc.createParagraph();
            h2_2.setStyle("Heading 2");
            h2_2.createRun().setText("1.2 通信");

            // H2_2 下正文
            doc.createParagraph().createRun().setText("通信说明。");

            // 第二个 H1（回退，重置全部）
            XWPFParagraph h1_2 = doc.createParagraph();
            h1_2.setStyle("Heading 1");
            h1_2.createRun().setText("第 2 章 部署");

            // 尾部正文
            doc.createParagraph().createRun().setText("部署说明。");
        });

        ParsedDocument parsed = parser.parse(source);

        // 前言：标题路径应明确为空列表 []，非 null
        assertThat(parsed.blocks().get(0).headingPath()).isEmpty();
        assertThat(parsed.blocks().get(0).type()).isEqualTo(ContentBlockType.TEXT);

        // H1
        ContentBlock b1 = parsed.blocks().get(1);
        assertThat(b1.type()).isEqualTo(ContentBlockType.HEADING);
        assertThat(b1.boundaryBefore()).isTrue();
        assertThat(b1.headingPath()).containsExactly(new Heading(1, "第 1 章 架构"));

        // H1 正文
        assertThat(parsed.blocks().get(2).headingPath()).containsExactly(new Heading(1, "第 1 章 架构"));

        // H2
        assertThat(parsed.blocks().get(3).headingPath()).containsExactly(
                new Heading(1, "第 1 章 架构"), new Heading(2, "1.1 模块"));

        // H3
        assertThat(parsed.blocks().get(5).headingPath()).containsExactly(
                new Heading(1, "第 1 章 架构"), new Heading(2, "1.1 模块"), new Heading(3, "1.1.1 存储"));

        // H2_2 (重置 H3)
        assertThat(parsed.blocks().get(7).headingPath()).containsExactly(
                new Heading(1, "第 1 章 架构"), new Heading(2, "1.2 通信"));

        // H1_2 (重置全部)
        assertThat(parsed.blocks().get(9).headingPath()).containsExactly(new Heading(1, "第 2 章 部署"));
        assertThat(parsed.blocks().get(10).headingPath()).containsExactly(new Heading(1, "第 2 章 部署"));

        assertLogicalOffsets(parsed);
    }

    @Test
    void resolvesHeadingFromExplicitParagraphOutline() {
        PreparedSource source = DocxTestSamples.poiDocx(doc -> {
            // p0: 显式 outline 0 -> Heading 1
            XWPFParagraph p0 = doc.createParagraph();
            setOutlineLvl(p0, 0);
            p0.createRun().setText("显式大纲一级");

            // p1: 显式 outline 2 -> Heading 3
            XWPFParagraph p1 = doc.createParagraph();
            setOutlineLvl(p1, 2);
            p1.createRun().setText("显式大纲三级");

            // p2: 显式 outline 8 -> Heading 9
            XWPFParagraph p2 = doc.createParagraph();
            setOutlineLvl(p2, 8);
            p2.createRun().setText("显式大纲九级");
        });

        ParsedDocument parsed = parser.parse(source);
        assertThat(parsed.blocks()).hasSize(3);
        assertThat(parsed.blocks()).allMatch(b -> b.type() == ContentBlockType.HEADING);
        assertThat(parsed.blocks().get(0).headingPath()).containsExactly(new Heading(1, "显式大纲一级"));
        assertThat(parsed.blocks().get(1).headingPath()).containsExactly(
                new Heading(1, "显式大纲一级"), new Heading(3, "显式大纲三级"));
        assertThat(parsed.blocks().get(2).headingPath()).containsExactly(
                new Heading(1, "显式大纲一级"), new Heading(3, "显式大纲三级"), new Heading(9, "显式大纲九级"));
    }

    @Test
    void explicitOutline9BlocksStyleFallback() {
        // 段落指定了 Heading 1 样式，但显式 outlineLvl=9（Word 正文级别）
        // 显式 9 必须作为正文，并且阻止回退到样式的 Heading 1
        String docXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:pPr>
                        <w:pStyle w:val="Heading1"/>
                        <w:outlineLvl w:val="9"/>
                      </w:pPr>
                      <w:r><w:t>带有标题样式但显式大纲9的段落</w:t></w:r>
                    </w:p>
                  </w:body>
                </w:document>
                """;

        String stylesXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:style w:type="paragraph" w:styleId="Heading1">
                    <w:name w:val="heading 1"/>
                    <w:pPr><w:outlineLvl w:val="0"/></w:pPr>
                  </w:style>
                </w:styles>
                """;

        PreparedSource source = DocxTestSamples.documentXmlDocx(docXml, stylesXml);
        ParsedDocument parsed = parser.parse(source);

        assertThat(parsed.blocks()).hasSize(1);
        ContentBlock block = parsed.blocks().getFirst();
        // 显式 9 阻止回退，判定为普通 TEXT
        assertThat(block.type()).isEqualTo(ContentBlockType.TEXT);
        assertThat(block.boundaryBefore()).isFalse();
        assertThat(block.headingPath()).isEmpty();
    }

    @Test
    void resolvesHeadingThroughStyleInheritanceChain() {
        String docXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:pPr><w:pStyle w:val="CustomChild"/></w:pPr>
                      <w:r><w:t>基于父样式继承的标题</w:t></w:r>
                    </w:p>
                  </w:body>
                </w:document>
                """;

        String stylesXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:style w:type="paragraph" w:styleId="BaseHeading">
                    <w:name w:val="My Base"/>
                    <w:pPr><w:outlineLvl w:val="1"/></w:pPr>
                  </w:style>
                  <w:style w:type="paragraph" w:styleId="CustomChild">
                    <w:name w:val="Custom Section"/>
                    <w:basedOn w:val="BaseHeading"/>
                  </w:style>
                </w:styles>
                """;

        PreparedSource source = DocxTestSamples.documentXmlDocx(docXml, stylesXml);
        ParsedDocument parsed = parser.parse(source);

        assertThat(parsed.blocks()).hasSize(1);
        ContentBlock block = parsed.blocks().getFirst();
        assertThat(block.type()).isEqualTo(ContentBlockType.HEADING);
        assertThat(block.boundaryBefore()).isTrue();
        assertThat(block.headingPath()).containsExactly(new Heading(2, "基于父样式继承的标题"));
    }

    @Test
    void rejectsStyleInheritanceCycle() {
        String docXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:pPr><w:pStyle w:val="StyleA"/></w:pPr>
                      <w:r><w:t>循环继承测试</w:t></w:r>
                    </w:p>
                  </w:body>
                </w:document>
                """;

        String stylesXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:style w:type="paragraph" w:styleId="StyleA">
                    <w:name w:val="Style A"/>
                    <w:basedOn w:val="StyleB"/>
                  </w:style>
                  <w:style w:type="paragraph" w:styleId="StyleB">
                    <w:name w:val="Style B"/>
                    <w:basedOn w:val="StyleA"/>
                  </w:style>
                </w:styles>
                """;

        PreparedSource source = DocxTestSamples.documentXmlDocx(docXml, stylesXml);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(source))
                .withMessage("DOCX 样式存在循环继承");
    }

    @Test
    void rejectsStyleInheritanceExcessiveDepth() {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                """);
        for (int i = 0; i <= 35; i++) {
            String basedOn = (i < 35) ? "<w:basedOn w:val=\"Style" + (i + 1) + "\"/>" : "";
            sb.append("<w:style w:type=\"paragraph\" w:styleId=\"Style").append(i).append("\">")
                    .append(basedOn)
                    .append("</w:style>");
        }
        sb.append("</w:styles>");

        String docXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:pPr><w:pStyle w:val="Style0"/></w:pPr>
                      <w:r><w:t>超深样式测试</w:t></w:r>
                    </w:p>
                  </w:body>
                </w:document>
                """;

        PreparedSource source = DocxTestSamples.documentXmlDocx(docXml, sb.toString());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(source))
                .withMessage("DOCX 样式继承层级超过解析限制");
    }

    @Test
    void parsesTablesWithTabsRowsAndPreservesEmptyCells() {
        PreparedSource source = DocxTestSamples.poiDocx(doc -> {
            XWPFTable table = doc.createTable(2, 3);
            // 行 0：A、空、C
            table.getRow(0).getCell(0).setText("A");
            table.getRow(0).getCell(1).setText(""); // 空单元格
            table.getRow(0).getCell(2).setText("C");

            // 行 1：空、B、空
            table.getRow(1).getCell(0).setText("");
            table.getRow(1).getCell(1).setText("B");
            table.getRow(1).getCell(2).setText("");
        });

        ParsedDocument parsed = parser.parse(source);

        assertThat(parsed.blocks()).hasSize(1);
        ContentBlock block = parsed.blocks().getFirst();
        assertThat(block.type()).isEqualTo(ContentBlockType.TABLE);
        // 列以 \t，行以 \n，空单元格保留
        assertThat(block.text()).isEqualTo("A\t\tC\n\tB\t");
        assertLogicalOffsets(parsed);
    }

    @Test
    void joinsMultiParagraphCellWithLf() {
        PreparedSource source = DocxTestSamples.poiDocx(doc -> {
            XWPFTable table = doc.createTable(1, 2);
            XWPFTableCell c0 = table.getRow(0).getCell(0);
            c0.setText("单元格0行1");
            XWPFParagraph p2 = c0.addParagraph();
            p2.createRun().setText("单元格0行2");

            XWPFTableCell c1 = table.getRow(0).getCell(1);
            c1.setText("单元格1单行");
        });

        ParsedDocument parsed = parser.parse(source);

        assertThat(parsed.blocks()).hasSize(1);
        ContentBlock block = parsed.blocks().getFirst();
        assertThat(block.type()).isEqualTo(ContentBlockType.TABLE);
        assertThat(block.text()).isEqualTo("单元格0行1\n单元格0行2\t单元格1单行");
        assertLogicalOffsets(parsed);
    }

    @Test
    void rejectsNestedTablesExplicitly() {
        // 手工 XML 构造嵌套表格：<w:tbl> 包含 <w:tc>，内部嵌套 <w:tbl>
        String docXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:tbl>
                      <w:tr>
                        <w:tc>
                          <w:p><w:r><w:t>外层单元格</w:t></w:r></w:p>
                          <w:tbl>
                            <w:tr>
                              <w:tc><w:p><w:r><w:t>嵌套表格内容</w:t></w:r></w:p></w:tc>
                            </w:tr>
                          </w:tbl>
                        </w:tc>
                      </w:tr>
                    </w:tbl>
                  </w:body>
                </w:document>
                """;

        PreparedSource source = DocxTestSamples.documentXmlDocx(docXml, null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(source))
                .withMessage("不支持嵌套表格");
    }

    @Test
    void skipsEmptyTablesAndParagraphs() {
        PreparedSource source = DocxTestSamples.poiDocx(doc -> {
            // 空段落
            doc.createParagraph().createRun().setText("");
            doc.createParagraph().createRun().setText("   ");

            // 有效段落
            doc.createParagraph().createRun().setText("有效正文");

            // 全部单元格为空的表格
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("");
            table.getRow(0).getCell(1).setText("");
            table.getRow(1).getCell(0).setText("");
            table.getRow(1).getCell(1).setText("");
        });

        ParsedDocument parsed = parser.parse(source);

        assertThat(parsed.blocks()).hasSize(1);
        assertThat(parsed.blocks().getFirst().text()).isEqualTo("有效正文");
        assertLogicalOffsets(parsed);
    }

    @Test
    void extractsHyperlinkTextWithoutVisitingExternalResources() {
        // 手工构造带有外部不可达超链接的文档
        String docXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <w:body>
                    <w:p>
                      <w:r><w:t>请点击：</w:t></w:r>
                      <w:hyperlink r:id="rIdLink">
                        <w:r><w:t>官方说明文档</w:t></w:r>
                      </w:hyperlink>
                      <w:r><w:t>继续阅读。</w:t></w:r>
                    </w:p>
                  </w:body>
                </w:document>
                """;

        String relsXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rIdLink" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
                                Target="http://127.0.0.1:54321/secret-url-never-visited" TargetMode="External"/>
                </Relationships>
                """;

        Map<String, byte[]> map = new HashMap<>();
        map.put("word/document.xml", docXml.getBytes(StandardCharsets.UTF_8));
        map.put("word/_rels/document.xml.rels", relsXml.getBytes(StandardCharsets.UTF_8));

        PreparedSource source = DocxTestSamples.customZipDocx(map);
        ParsedDocument parsed = parser.parse(source);

        assertThat(parsed.blocks()).hasSize(1);
        // 超链接文本正确并入段落正文，且不发起外部访问
        assertThat(parsed.blocks().getFirst().text()).isEqualTo("请点击：官方说明文档继续阅读。");
        assertLogicalOffsets(parsed);
    }

    @Test
    void excludesHeadersFootersAndFootnotes() {
        PreparedSource source = DocxTestSamples.poiDocx(doc -> {
            // 页眉
            org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
            policy.createHeader(org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy.DEFAULT)
                    .createParagraph().createRun().setText("这是页眉内容");

            // 页脚
            policy.createFooter(org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy.DEFAULT)
                    .createParagraph().createRun().setText("这是页脚内容");

            // 正文段落
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText("正文第一段。");
        });

        ParsedDocument parsed = parser.parse(source);

        assertThat(parsed.blocks()).hasSize(1);
        assertThat(parsed.blocks().getFirst().text()).isEqualTo("正文第一段。");
        assertThat(parsed.blocks().getFirst().text()).doesNotContain("页眉", "页脚");
        assertLogicalOffsets(parsed);
    }

    @Test
    void calculatesExactUtf16OffsetWithLogicalFullText() {
        PreparedSource source = DocxTestSamples.poiDocx(doc -> {
            // 中文、emoji、特殊标点与换行
            XWPFParagraph p0 = doc.createParagraph();
            p0.createRun().setText("🌟 欢迎使用 DOCX 解析器 🚀\n第二行正文。");

            XWPFParagraph p1 = doc.createParagraph();
            p1.createRun().setText("纯 ASCII 文本与 tab\t内容。");

            XWPFParagraph p2 = doc.createParagraph();
            p2.createRun().setText("末尾段落 🎉。");
        });

        ParsedDocument parsed = parser.parse(source);
        assertThat(parsed.blocks()).hasSize(3);

        String fullText = parsed.blocks().stream()
                .map(ContentBlock::text)
                .collect(Collectors.joining("\n\n"));

        for (ContentBlock block : parsed.blocks()) {
            assertThat(block.source()).isNotNull();
            assertThat(block.source().pageStart()).isNull();
            assertThat(block.source().pageEnd()).isNull();

            int start = block.source().startOffset();
            int end = block.source().endOffset();
            assertThat(fullText.substring(start, end)).isEqualTo(block.text());
        }
    }

    @Test
    void preflightRejectsArbitraryZipWithoutDocxStructure() {
        // 普通 ZIP，包含 foo.txt，但缺少 [Content_Types].xml 与 word/document.xml
        Map<String, byte[]> map = Map.of("foo.txt", "hello".getBytes(StandardCharsets.UTF_8));
        try (var out = new ByteArrayOutputStream();
             var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("foo.txt"));
            zip.write("hello".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();

            PreparedSource source = ParserTestSources.source(out.toByteArray(), FileFormat.DOCX);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> parser.parse(source))
                    .withMessage("DOCX 格式无效或已损坏");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void preflightRejectsZipSlipPathTraversal() {
        Map<String, byte[]> map = new HashMap<>();
        map.put("word/../../etc/passwd", "root:x:0:0".getBytes(StandardCharsets.UTF_8));
        map.put("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8));
        map.put("word/document.xml", "<w:document/>".getBytes(StandardCharsets.UTF_8));

        try (var out = new ByteArrayOutputStream();
             var zip = new ZipOutputStream(out)) {
            for (var e : map.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
            zip.finish();
            PreparedSource source = ParserTestSources.source(out.toByteArray(), FileFormat.DOCX);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> parser.parse(source))
                    .withMessage("DOCX 格式无效或已损坏");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void preflightRejectsDtdAndExternalEntities() {
        String xxeDocXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE w:document [
                  <!ENTITY xxe SYSTEM "http://127.0.0.1:9999/malicious">
                ]>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body>
                </w:document>
                """;

        PreparedSource source = DocxTestSamples.documentXmlDocx(xxeDocXml, null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(source))
                .withMessage("DOCX 包含不受支持的外部实体或 DTD 声明");
    }

    @Test
    void preflightRejectsExcessiveZipEntries() {
        try (var out = new ByteArrayOutputStream();
             var zip = new ZipOutputStream(out)) {
            for (int i = 0; i <= 1005; i++) {
                zip.putNextEntry(new ZipEntry("entry_" + i + ".xml"));
                zip.write("<a/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            PreparedSource source = ParserTestSources.source(out.toByteArray(), FileFormat.DOCX);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> parser.parse(source))
                    .withMessage("DOCX 条目数量超过解析限制");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void preflightRejectsExcessiveXmlDepth() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">\n<w:body>");
        for (int i = 0; i < 135; i++) {
            sb.append("<w:customNode").append(i).append(">");
        }
        sb.append("<w:p><w:r><w:t>超深 XML</w:t></w:r></w:p>");
        for (int i = 134; i >= 0; i--) {
            sb.append("</w:customNode").append(i).append(">");
        }
        sb.append("</w:body></w:document>");

        PreparedSource source = DocxTestSamples.documentXmlDocx(sb.toString(), null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(source))
                .withMessage("DOCX XML 结构嵌套层级超过解析限制");
    }

    @Test
    void rejectsEmptyCorruptedAndMismatchedSource() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(null))
                .withMessage("源文件对象不能为空");

        PreparedSource wrongFormat = ParserTestSources.source("test", FileFormat.TXT);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(wrongFormat))
                .withMessage("源文件格式不匹配");

        PreparedSource emptySource = ParserTestSources.source(new byte[0], FileFormat.DOCX);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(emptySource))
                .withMessage("文档内容不能为空白");

        PreparedSource corruptBytes = ParserTestSources.source(new byte[]{1, 2, 3, 4}, FileFormat.DOCX);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(corruptBytes))
                .withMessage("DOCX 格式无效或已损坏");

        DocxKnowledgeDocumentParser smallParser = new DocxKnowledgeDocumentParser(10);
        PreparedSource largerSource = DocxTestSamples.poiDocx(doc -> doc.createParagraph().createRun().setText("test"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> smallParser.parse(largerSource))
                .withMessage("源文件大小超过预算限制");
    }

    @Test
    void rejectsDocumentWithNoValidContent() {
        PreparedSource source = DocxTestSamples.poiDocx(doc -> {
            doc.createParagraph().createRun().setText("   ");
            doc.createParagraph().createRun().setText("\n\t  ");
        });

        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(source))
                .withMessage("文档没有可解析的有效内容");
    }

    @Test
    void toStringDoesNotLeakFullText() {
        PreparedSource source = DocxTestSamples.poiDocx(doc -> {
            XWPFParagraph p = doc.createParagraph();
            p.setStyle("Heading 1");
            p.createRun().setText("秘密项目标题");

            doc.createParagraph().createRun().setText("保密核心正文内容。");
        });

        ParsedDocument parsed = parser.parse(source);
        assertThat(parsed.toString()).doesNotContain("秘密项目标题", "保密核心正文内容");
        for (ContentBlock block : parsed.blocks()) {
            assertThat(block.toString()).doesNotContain("秘密项目标题", "保密核心正文内容");
        }
    }

    @Test
    void preflightRejectsUtf16StylesXmlDtd() {
        String stylesXml = """
                <?xml version="1.0" encoding="UTF-16"?>
                <!DOCTYPE styles [
                  <!ENTITY xxe SYSTEM "http://127.0.0.1:9999/malicious">
                ]>
                <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:style w:type="paragraph" w:styleId="Normal">
                    <w:name w:val="Normal"/>
                  </w:style>
                </w:styles>
                """;

        String docXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>正文</w:t></w:r></w:p></w:body>
                </w:document>
                """;

        Map<String, byte[]> map = new HashMap<>();
        map.put("word/document.xml", docXml.getBytes(StandardCharsets.UTF_8));
        map.put("word/styles.xml", stylesXml.getBytes(StandardCharsets.UTF_16));

        PreparedSource source = DocxTestSamples.customZipDocx(map);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(source))
                .withMessage("DOCX 包含不受支持的外部实体或 DTD 声明")
                .satisfies(e -> assertThat(e.getCause()).isNull());
    }

    @Test
    void preflightRejectsExcessiveStylesXmlDepth() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">\n");
        for (int i = 0; i < 135; i++) {
            sb.append("<w:customStyleNode").append(i).append(">");
        }
        sb.append("<w:style w:type=\"paragraph\" w:styleId=\"Normal\"/>");
        for (int i = 134; i >= 0; i--) {
            sb.append("</w:customStyleNode").append(i).append(">");
        }
        sb.append("</w:styles>");

        String docXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>正文</w:t></w:r></w:p></w:body>
                </w:document>
                """;

        Map<String, byte[]> map = new HashMap<>();
        map.put("word/document.xml", docXml.getBytes(StandardCharsets.UTF_8));
        map.put("word/styles.xml", sb.toString().getBytes(StandardCharsets.UTF_8));

        PreparedSource source = DocxTestSamples.customZipDocx(map);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(source))
                .withMessage("DOCX XML 结构嵌套层级超过解析限制");
    }

    @Test
    void preflightRejectsDuplicateZipEntries() {
        byte[] docXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>hello</w:t></w:r></w:p></w:body>
                </w:document>
                """.stripIndent().getBytes(StandardCharsets.UTF_8);

        PreparedSource source = DocxTestSamples.duplicateZipDocx("word/document.xml", docXml);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(source))
                .withMessage("DOCX 包含重复的 ZIP 条目");
    }

    @Test
    void rejectsTopLevelSdtContainer() {
        String docXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:sdt>
                      <w:sdtContent>
                        <w:p><w:r><w:t>SDT顶层内容</w:t></w:r></w:p>
                      </w:sdtContent>
                    </w:sdt>
                  </w:body>
                </w:document>
                """;

        PreparedSource source = DocxTestSamples.documentXmlDocx(docXml, null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(source))
                .withMessage("不支持的内容容器");
    }

    @Test
    void rejectsTopLevelAltChunkContainer() {
        String docXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <w:body>
                    <w:p><w:r><w:t>正文段落</w:t></w:r></w:p>
                    <w:altChunk r:id="rIdChunk1"/>
                  </w:body>
                </w:document>
                """;

        PreparedSource source = DocxTestSamples.documentXmlDocx(docXml, null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(source))
                .withMessage("不支持的内容容器");
    }

    @Test
    void rejectsMathFormulas() {
        String docXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                            xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math">
                  <w:body>
                    <w:p>
                      <m:oMath>
                        <m:r><m:t>E=mc^2</m:t></m:r>
                      </m:oMath>
                    </w:p>
                  </w:body>
                </w:document>
                """;

        PreparedSource source = DocxTestSamples.documentXmlDocx(docXml, null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(source))
                .withMessage("不支持数学公式");
    }

    @Test
    void retainsPlainTextMentioningUnsupportedXmlElementNames() {
        String text = "textbox、txbxContent 和 wps:txbx 是文档中的技术术语。";
        PreparedSource source = DocxTestSamples.poiDocx(doc ->
                doc.createParagraph().createRun().setText(text));

        assertThat(parser.parse(source).blocks()).extracting(ContentBlock::text).containsExactly(text);
    }

    @Test
    void rejectsTextBoxes() {
        String docXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:r>
                        <w:txbxContent>
                          <w:p><w:r><w:t>文本框内容</w:t></w:r></w:p>
                        </w:txbxContent>
                      </w:r>
                    </w:p>
                  </w:body>
                </w:document>
                """;

        PreparedSource source = DocxTestSamples.documentXmlDocx(docXml, null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(source))
                .withMessage("不支持文本框");
    }

    @Test
    void rejectsEmbeddedPictures() {
        String docXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                            xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"
                            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">
                  <w:body>
                    <w:p>
                      <w:r>
                        <w:drawing>
                          <wp:inline>
                            <a:graphic>
                              <a:graphicData>
                                <pic:pic>
                                  <pic:blipFill><a:blip/></pic:blipFill>
                                </pic:pic>
                              </a:graphicData>
                            </a:graphic>
                          </wp:inline>
                        </w:drawing>
                      </w:r>
                    </w:p>
                  </w:body>
                </w:document>
                """;

        PreparedSource source = DocxTestSamples.documentXmlDocx(docXml, null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(source))
                .withMessage("不支持图片");
    }

    @Test
    void rejectsIllegalAndOverflowOutlineLevels() {
        PreparedSource overflowParagraph = DocxTestSamples.poiDocx(doc -> {
            XWPFParagraph p = doc.createParagraph();
            CTP ctp = p.getCTP();
            CTPPr ppr = ctp.addNewPPr();
            CTDecimalNumber lvl = ppr.addNewOutlineLvl();
            lvl.setVal(new BigInteger("4294967296"));
            p.createRun().setText("大纲溢出段落");
        });
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(overflowParagraph))
                .withMessage("DOCX 大纲层级非法");

        PreparedSource negativeParagraph = DocxTestSamples.poiDocx(doc -> {
            XWPFParagraph p = doc.createParagraph();
            CTP ctp = p.getCTP();
            CTPPr ppr = ctp.addNewPPr();
            CTDecimalNumber lvl = ppr.addNewOutlineLvl();
            lvl.setVal(BigInteger.valueOf(-1));
            p.createRun().setText("负数大纲段落");
        });
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(negativeParagraph))
                .withMessage("DOCX 大纲层级非法");

        PreparedSource excessiveParagraph = DocxTestSamples.poiDocx(doc -> {
            XWPFParagraph p = doc.createParagraph();
            CTP ctp = p.getCTP();
            CTPPr ppr = ctp.addNewPPr();
            CTDecimalNumber lvl = ppr.addNewOutlineLvl();
            lvl.setVal(BigInteger.valueOf(10));
            p.createRun().setText("超标大纲段落");
        });
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(excessiveParagraph))
                .withMessage("DOCX 大纲层级非法");

        String stylesXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:style w:type="paragraph" w:styleId="OverflowStyle">
                    <w:name w:val="Overflow Style"/>
                    <w:pPr><w:outlineLvl w:val="4294967296"/></w:pPr>
                  </w:style>
                </w:styles>
                """;
        String docXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:pPr><w:pStyle w:val="OverflowStyle"/></w:pPr>
                      <w:r><w:t>样式大纲溢出</w:t></w:r>
                    </w:p>
                  </w:body>
                </w:document>
                """;
        PreparedSource overflowStyleDoc = DocxTestSamples.documentXmlDocx(docXml, stylesXml);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(overflowStyleDoc))
                .withMessage("DOCX 大纲层级非法");
    }

    @Test
    void exceptionMessagesDoNotLeakCausesOrRawXml() {
        PreparedSource corruptXml = DocxTestSamples.documentXmlDocx("<w:document><unclosed", null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(corruptXml))
                .withMessage("DOCX 格式无效或已损坏")
                .satisfies(e -> assertThat(e.getCause()).isNull());

        String xxeDocXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE w:document [
                  <!ENTITY xxe SYSTEM "http://127.0.0.1:9999/malicious-leak-test">
                ]>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body>
                </w:document>
                """;
        PreparedSource xxeSource = DocxTestSamples.documentXmlDocx(xxeDocXml, null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(xxeSource))
                .withMessage("DOCX 包含不受支持的外部实体或 DTD 声明")
                .satisfies(e -> {
                    assertThat(e.getCause()).isNull();
                    assertThat(e.getMessage()).doesNotContain("127.0.0.1", "malicious-leak-test", "xxe");
                });
    }

    private static void setOutlineLvl(XWPFParagraph paragraph, int level) {
        CTP ctp = paragraph.getCTP();
        CTPPr ppr = ctp.isSetPPr() ? ctp.getPPr() : ctp.addNewPPr();
        CTDecimalNumber outlineLvl = ppr.isSetOutlineLvl() ? ppr.getOutlineLvl() : ppr.addNewOutlineLvl();
        outlineLvl.setVal(BigInteger.valueOf(level));
    }

    private static void assertLogicalOffsets(ParsedDocument parsed) {
        String fullText = parsed.blocks().stream()
                .map(ContentBlock::text)
                .collect(Collectors.joining("\n\n"));

        int previousEnd = 0;
        for (int i = 0; i < parsed.blocks().size(); i++) {
            ContentBlock block = parsed.blocks().get(i);
            assertThat(block.ordinal()).isEqualTo(i);
            assertThat(block.source()).isNotNull();
            assertThat(block.source().pageStart()).isNull();
            assertThat(block.source().pageEnd()).isNull();

            int start = block.source().startOffset();
            int end = block.source().endOffset();
            assertThat(start).isEqualTo(previousEnd);
            assertThat(fullText.substring(start, end)).isEqualTo(block.text());
            previousEnd = end + 2; // "\n\n"
        }
    }
}
