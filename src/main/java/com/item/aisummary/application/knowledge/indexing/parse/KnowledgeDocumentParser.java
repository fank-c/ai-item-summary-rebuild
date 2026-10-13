package com.item.aisummary.application.knowledge.indexing.parse;

import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.application.knowledge.indexing.source.PreparedSource;

/**
 * 文档格式解析策略能力端口（Port）。
 *
 * <p>按文件格式（{@link FileFormat}）接入具体的格式解析实现，
 * 将已经过大小和 SHA-256 校验的原始字节转换为统一的结构化内容快照（{@link ParsedDocument}）。
 *
 * <p>实现约束：
 * <ul>
 *   <li>只负责把受控源文件字节转换为有序内容块列表，不负责分块（Chunking）、不分配数据库 ID、不写入外部数据库或存储。</li>
 *   <li>解析实现必须自行校验真实格式有效性；文本解析实现（如 TXT、MD）需负责严格的 UTF-8 校验，二进制格式（如 PDF、DOCX）不经过公共 UTF-8 校验。</li>
 *   <li>必须防范解析过程中的内存和数据膨胀（如 DOCX 解压炸弹）。</li>
 *   <li>标题路径、行内代码、表格及页码来源位置只在能够可靠提取时提供，无法可靠获得时保持 null，不虚构或降级伪造。</li>
 *   <li>打开的内存输入流必须由解析实现负责在方法执行完毕前关闭。</li>
 * </ul>
 */
public interface KnowledgeDocumentParser {
    /**
     * 该策略支持解析的文件格式。
     *
     * @return 文件格式枚举
     */
    FileFormat format();

    /**
     * 解析已准备好的源文件内容。
     *
     * @param source 包含原始受控字节与元数据的源文件包装对象
     * @return 解析完成的统一结构化文档；必须非空且包含从 0 连续序号的阅读内容块
     */
    ParsedDocument parse(PreparedSource source);
}
