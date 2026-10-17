package com.item.aisummary.infrastructure.knowledge.indexing.parse.docx;

import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.application.knowledge.indexing.source.PreparedSource;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.support.ParserTestSources;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;

/**
 * DOCX 解析器单元测试辅助数据生成器。
 *
 * <p>支持生成两类测试源：
 * <ul>
 *   <li>真实 POI 生成的小样本（{@link #poiDocx(Consumer)}）：使用真实 {@link XWPFDocument} 构建标准段落、表格、样式与超链接。</li>
 *   <li>手工定制 ZIP/XML 增强样本（{@link #customZipDocx(Map)}）：精确控制 ZIP 条目、XML 实体、DTD、嵌套层级与损坏结构，用于边界及恶意样本防御验证。</li>
 * </ul>
 */
final class DocxTestSamples {
    /** 本地生成图片条目所用的 1×1 PNG，不含外部地址或业务数据。 */
    private static final byte[] PNG_BYTES = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGNgAAIAAAUAAXpeqz8AAAAASUVORK5CYII=");

    private DocxTestSamples() {}

    /** 为指定 Run 插入真实内嵌 PNG，使测试同时覆盖媒体条目与正文关系引用。 */
    static void addPicture(XWPFRun run) {
        try (var image = new ByteArrayInputStream(PNG_BYTES)) {
            run.addPicture(image, XWPFDocument.PICTURE_TYPE_PNG, "fixture.png", 9525, 9525);
        } catch (Exception e) {
            throw new IllegalStateException("DOCX 图片样本构建失败", e);
        }
    }

    /**
     * 基于真实 POI API 构造受控 DOCX 测试源。
     */
    static PreparedSource poiDocx(Consumer<XWPFDocument> consumer) {
        try (var doc = new XWPFDocument();
             var out = new ByteArrayOutputStream()) {
            consumer.accept(doc);
            doc.write(out);
            return ParserTestSources.source(out.toByteArray(), FileFormat.DOCX);
        } catch (IOException e) {
            throw new IllegalStateException("POI 测试样本构建失败", e);
        }
    }

    /**
     * 构造手工定制 ZIP 与 XML 结构的 DOCX 测试源。
     *
     * <p>默认提供最小合规的 [Content_Types].xml、_rels/.rels 和 word/_rels/document.xml.rels，
     * 调用方可通过 entries 覆盖或新增任何条目（例如 word/document.xml 或 word/styles.xml）。
     */
    static PreparedSource customZipDocx(Map<String, byte[]> customEntries) {
        Map<String, byte[]> entries = new HashMap<>();

        // 默认最小 OpenXML 包装
        entries.put("[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
                </Types>
                """.stripIndent().getBytes(StandardCharsets.UTF_8));

        entries.put("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
                """.stripIndent().getBytes(StandardCharsets.UTF_8));

        entries.put("word/_rels/document.xml.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>
                """.stripIndent().getBytes(StandardCharsets.UTF_8));

        if (customEntries != null) {
            entries.putAll(customEntries);
        }

        try (var out = new ByteArrayOutputStream();
             var zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return ParserTestSources.source(out.toByteArray(), FileFormat.DOCX);
        } catch (IOException e) {
            throw new IllegalStateException("手工 ZIP 样本构建失败", e);
        }
    }

    /**
     * 便捷方法：构造仅包含自定义 word/document.xml 与可选 word/styles.xml 的手工 DOCX。
     */
    static PreparedSource documentXmlDocx(String documentXml, String stylesXml) {
        Map<String, byte[]> map = new HashMap<>();
        map.put("word/document.xml", documentXml.getBytes(StandardCharsets.UTF_8));
        if (stylesXml != null) {
            map.put("word/styles.xml", stylesXml.getBytes(StandardCharsets.UTF_8));
        }
        return customZipDocx(map);
    }

    /**
     * 构造包含重复 ZIP 条目的手工 DOCX 样本（基于底层 Local File Header 手工字节构造）。
     */
    static PreparedSource duplicateZipDocx(String duplicateName, byte[] duplicateContent) {
        try (var out = new ByteArrayOutputStream()) {
            byte[] contentTypes = """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """.stripIndent().getBytes(StandardCharsets.UTF_8);
            byte[] docXml = """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>hello</w:t></w:r></w:p></w:body>
                    </w:document>
                    """.stripIndent().getBytes(StandardCharsets.UTF_8);

            writeLocalZipEntry(out, "[Content_Types].xml", contentTypes);
            writeLocalZipEntry(out, "word/document.xml", docXml);
            writeLocalZipEntry(out, duplicateName, duplicateContent);
            return ParserTestSources.source(out.toByteArray(), FileFormat.DOCX);
        } catch (IOException e) {
            throw new IllegalStateException("重复 ZIP 样本构建失败", e);
        }
    }

    private static void writeLocalZipEntry(ByteArrayOutputStream out, String name, byte[] content) throws IOException {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(content);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);

        out.write(new byte[]{0x50, 0x4b, 0x03, 0x04});
        out.write(new byte[]{20, 0});
        out.write(new byte[]{0, 0});
        out.write(new byte[]{0, 0});
        out.write(new byte[]{0, 0, 0, 0});

        long c = crc.getValue();
        out.write(new byte[]{(byte) (c & 0xff), (byte) ((c >> 8) & 0xff), (byte) ((c >> 16) & 0xff), (byte) ((c >> 24) & 0xff)});
        int len = content.length;
        out.write(new byte[]{(byte) (len & 0xff), (byte) ((len >> 8) & 0xff), (byte) ((len >> 16) & 0xff), (byte) ((len >> 24) & 0xff)});
        out.write(new byte[]{(byte) (len & 0xff), (byte) ((len >> 8) & 0xff), (byte) ((len >> 16) & 0xff), (byte) ((len >> 24) & 0xff)});
        out.write(new byte[]{(byte) (nameBytes.length & 0xff), (byte) ((nameBytes.length >> 8) & 0xff)});
        out.write(new byte[]{0, 0});
        out.write(nameBytes);
        out.write(content);
    }
}
