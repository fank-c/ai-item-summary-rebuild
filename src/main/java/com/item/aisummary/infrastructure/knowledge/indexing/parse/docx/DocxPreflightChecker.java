package com.item.aisummary.infrastructure.knowledge.indexing.parse.docx;

import com.item.aisummary.application.knowledge.indexing.source.PreparedSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * DOCX 源文件解压与 XML 结构预算前置安全检查器。
 *
 * <p>在 Apache POI 实例化文档前，对原始 ZIP 流及 XML 结构执行防御与预算核验：
 * <ul>
 *   <li>验证必需组成部分（[Content_Types].xml 与 word/document.xml），校验 ZipSlip 与重复条目。</li>
 *   <li>限制 ZIP 条目总数、单条目展开大小、全部展开总大小与压缩解压比率。</li>
 *   <li>对所有 .xml 与 .rels 条目使用 StAX 强制禁用 DTD 与外部实体解析，并拒绝 DTD 事件。</li>
 *   <li>在 StAX 遍历中校验 XML 嵌套深度、总字符预算、正文顶层容器、段落不支持元素及嵌套表格；普通图片允许由正文解析器跳过，媒体条目仍计入 ZIP 预算。</li>
 * </ul>
 */
final class DocxPreflightChecker {
    /** ZIP 包内允许的最大条目总数防御上限。 */
    static final int MAX_ZIP_ENTRIES = 1_000;
    /** ZIP 单个条目解压后的最大字节预算（50MiB）。 */
    static final long MAX_SINGLE_ENTRY_SIZE = 50 * 1024 * 1024L;
    /** ZIP 全部条目展开后的最大累计字节预算（100MiB）。 */
    static final long MAX_TOTAL_UNCOMPRESSED_SIZE = 100 * 1024 * 1024L;
    /** 针对解压大小超过 1MB 的条目或文档允许的最大压缩比上限（100:1），防范 ZIP 炸弹。 */
    static final long MAX_COMPRESSION_RATIO = 100;
    /** 启用压缩解压比检查的最小解压字节阈值（1MiB）。 */
    static final long MIN_SIZE_FOR_RATIO_CHECK = 1024 * 1024L;
    /** XML 节点元素允许的最大嵌套深度防御上限。 */
    static final int MAX_XML_DEPTH = 128;
    /** XML 结构块（段落、表格）允许的最大数量防御上限。 */
    static final int MAX_BLOCKS = 100_000;
    /** XML 文本字符允许的最大累计长度防御上限。 */
    static final long MAX_TOTAL_CHARS = 50_000_000L;

    private DocxPreflightChecker() {}

    /**
     * 在 Apache POI 实例化文档前，对原始 ZIP 流及 XML 结构执行深度防御与预算核验。
     *
     * <p>核验步骤：
     * <ol>
     *   <li>遍历 ZIP 条目，校验条目路径合法性（防御 ZipSlip 路径穿越）；</li>
     *   <li>累计展开大小，比对单条目与总解压上限；</li>
     *   <li>核算解压压缩比，拦截 ZIP 炸弹攻击；</li>
     *   <li>确认包含必须的 {@code [Content_Types].xml} 与 {@code word/document.xml}；</li>
     *   <li>对所有 {@code .xml} 与 {@code .rels} 文件，使用安全 StAX Reader 执行 DTD/XXE 拦截与结构深度核验。</li>
     * </ol>
     *
     * @param source 准备就绪的源文件包装对象
     * @throws IllegalArgumentException 当检测到格式损坏、压缩比异常、超限条目或非法 XML 结构时抛出
     */
    static void preflight(PreparedSource source) {
        try (InputStream in = source.openStream();
             ZipInputStream zip = new ZipInputStream(in)) {

            Set<String> entryNames = new HashSet<>();
            int entryCount = 0;
            long totalUncompressed = 0;
            long[] totalXmlChars = new long[1];
            boolean hasContentTypes = false;
            boolean hasDocumentXml = false;
            byte[] documentXmlBytes = null;
            byte[] buffer = new byte[8192];
            XMLInputFactory factory = createXmlInputFactory();

            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw new IllegalArgumentException("DOCX 条目数量超过解析限制");
                }

                String name = entry.getName();
                if (name == null || name.isBlank() || name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                    throw new IllegalArgumentException("DOCX 格式无效或已损坏");
                }

                if (!entryNames.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("DOCX 包含重复的 ZIP 条目");
                }

                if ("[Content_Types].xml".equalsIgnoreCase(name)) {
                    hasContentTypes = true;
                } else if ("word/document.xml".equalsIgnoreCase(name)) {
                    hasDocumentXml = true;
                }

                long entryUncompressed = 0;
                ByteArrayOutputStream entryOut = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    entryUncompressed += read;
                    totalUncompressed += read;

                    if (entryUncompressed > MAX_SINGLE_ENTRY_SIZE) {
                        throw new IllegalArgumentException("DOCX 条目大小超过解析限制");
                    }
                    if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_SIZE) {
                        throw new IllegalArgumentException("DOCX 展开总大小超过解析限制");
                    }

                    entryOut.write(buffer, 0, read);
                }

                byte[] entryBytes = entryOut.toByteArray();
                if ("word/document.xml".equalsIgnoreCase(name)) {
                    documentXmlBytes = entryBytes;
                }

                long compressedSize = entry.getCompressedSize();
                if (compressedSize > 0 && entryUncompressed > MIN_SIZE_FOR_RATIO_CHECK) {
                    if (entryUncompressed / compressedSize > MAX_COMPRESSION_RATIO) {
                        throw new IllegalArgumentException("DOCX 压缩比异常");
                    }
                }

                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".xml") || lower.endsWith(".rels")) {
                    boolean isDocXml = "word/document.xml".equalsIgnoreCase(name);
                    inspectXmlWithStax(factory, entryBytes, isDocXml, totalXmlChars);
                }
            }

            if (totalUncompressed > MIN_SIZE_FOR_RATIO_CHECK && source.size() > 0) {
                if (totalUncompressed / source.size() > MAX_COMPRESSION_RATIO) {
                    throw new IllegalArgumentException("DOCX 压缩比异常");
                }
            }

            if (!hasContentTypes || !hasDocumentXml || documentXmlBytes == null) {
                throw new IllegalArgumentException("DOCX 格式无效或已损坏");
            }

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("DOCX 格式无效或已损坏");
        }
    }

    /**
     * 创建安全配置的 XMLInputFactory，全面关闭 DTD 支持与外部实体解析以防御 XXE。
     */
    private static XMLInputFactory createXmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, Boolean.FALSE);
        factory.setXMLResolver((publicID, systemID, baseURI, namespace) -> {
            throw new XMLStreamException("拒绝外部实体解析");
        });
        return factory;
    }

    /**
     * 使用 StAX 流式解析检查 XML 条目内容，对 XXE 声明、节点嵌套深度、特定 Word 结构元素进行轻量级预检。
     *
     * @param factory 已加固的 XMLInputFactory 实例
     * @param xmlBytes 条目原始 XML 字节数组
     * @param isDocumentXml 是否为核心正文文件 word/document.xml
     * @param totalXmlChars 累计提取字符数的单元素数组引用
     * @throws IllegalArgumentException 当包含 DTD/实体引用、超深度嵌套、嵌套表格或不支持容器时抛出
     */
    private static void inspectXmlWithStax(XMLInputFactory factory, byte[] xmlBytes, boolean isDocumentXml, long[] totalXmlChars) {
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xmlBytes));
            int depth = 0;
            int structureCount = 0;
            int tableNesting = 0;
            boolean inBody = false;
            int bodyNesting = 0;

            while (reader.hasNext()) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        depth++;
                        if (depth > MAX_XML_DEPTH) {
                            throw new IllegalArgumentException("DOCX XML 结构嵌套层级超过解析限制");
                        }
                        if (isDocumentXml) {
                            String local = reader.getLocalName();
                            if ("body".equals(local)) {
                                inBody = true;
                                bodyNesting = 0;
                            } else if (inBody) {
                                bodyNesting++;
                                if (bodyNesting == 1) {
                                    if ("sdt".equals(local) || "altChunk".equals(local) || "customXml".equals(local)) {
                                        throw new IllegalArgumentException("不支持的内容容器");
                                    }
                                }
                            }

                            if ("tbl".equals(local)) {
                                tableNesting++;
                                if (tableNesting > 1) {
                                    throw new IllegalArgumentException("不支持嵌套表格");
                                }
                                structureCount++;
                            } else if ("p".equals(local) && tableNesting == 0) {
                                structureCount++;
                            }

                            if ("oMath".equals(local) || "oMathPara".equals(local)) {
                                throw new IllegalArgumentException("不支持数学公式");
                            }
                            if ("txbxContent".equals(local) || "textbox".equals(local)
                                    || "txbx".equals(local) || "txBody".equals(local)) {
                                throw new IllegalArgumentException("不支持文本框");
                            }
                            // 普通图片仅跳过，不因配图拒绝整篇正文；OLE 等嵌入对象仍不在支持范围内。
                            if ("object".equals(local) || "OLEObject".equals(local) || "control".equals(local)) {
                                throw new IllegalArgumentException("不支持嵌入对象");
                            }

                            if (structureCount > MAX_BLOCKS) {
                                throw new IllegalArgumentException("DOCX 结构数量超过解析限制");
                            }
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        depth--;
                        if (isDocumentXml) {
                            String local = reader.getLocalName();
                            if ("tbl".equals(local)) {
                                tableNesting--;
                            }
                            if (inBody) {
                                if ("body".equals(local)) {
                                    inBody = false;
                                    bodyNesting = 0;
                                } else {
                                    bodyNesting--;
                                }
                            }
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        totalXmlChars[0] += reader.getTextLength();
                        if (totalXmlChars[0] > MAX_TOTAL_CHARS) {
                            throw new IllegalArgumentException("DOCX 总字符数超过解析限制");
                        }
                    }
                    case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE -> {
                        throw new IllegalArgumentException("DOCX 包含不受支持的外部实体或 DTD 声明");
                    }
                    default -> {}
                }
            }
            reader.close();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (XMLStreamException e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
            if (msg.contains("doctype") || msg.contains("dtd") || msg.contains("entity") || msg.contains("实体")) {
                throw new IllegalArgumentException("DOCX 包含不受支持的外部实体或 DTD 声明");
            }
            throw new IllegalArgumentException("DOCX 格式无效或已损坏");
        } catch (Exception e) {
            throw new IllegalArgumentException("DOCX 格式无效或已损坏");
        }
    }
}
