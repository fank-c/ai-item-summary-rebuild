package com.item.aisummary.infrastructure.knowledge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.item.aisummary.domain.knowledge.value.Heading;
import com.item.aisummary.infrastructure.knowledge.persistence.typehandler.HeadingPathTypeHandler;
import com.item.aisummary.infrastructure.persistence.typehandler.UtcInstantTypeHandler;
import java.time.Instant;
import java.util.List;

/** knowledge_chunk 表映射；标题路径通过专用 JSON 类型处理器往返。 */
@TableName(value = "knowledge_chunk", autoResultMap = true)
public final class KnowledgeChunkEntity {
    /** 片段 ID；由保存批次入口生成。 */
    @TableId(type = IdType.INPUT)
    public String id;

    /** 所属文档版本 ID；通过逻辑关联保存，不使用物理外键。 */
    public String revisionId;

    /** 同一版本内从 0 开始且唯一的片段序号。 */
    public int sequenceNo;

    /** 片段正文；保留处理后的原文，不在持久化层截断。 */
    public String content;

    /** 有序标题路径；通过 JSON 类型处理器与 heading_path 列互转。 */
    @TableField(value = "heading_path", typeHandler = HeadingPathTypeHandler.class)
    public List<Heading> headingPath;

    /** 使用的分词器统计值；无法确定时为空，不由当前服务计算。 */
    public Integer tokenCount;

    /** 处理后全文中的 UTF-16 起始偏移；未知时为空。 */
    public Integer startOffset;

    /** 处理后全文中的 UTF-16 结束偏移，不包含该位置；未知时为空。 */
    public Integer endOffset;

    /** 来源起始页，从 1 开始；未知时为空。 */
    public Integer pageStart;

    /** 来源结束页，包含该页；未知时为空。 */
    public Integer pageEnd;

    /** 片段创建时间；通过 UTC 类型处理器映射数据库的 DATETIME(6)。 */
    @TableField(value = "create_at", typeHandler = UtcInstantTypeHandler.class)
    public Instant createAt;
}
