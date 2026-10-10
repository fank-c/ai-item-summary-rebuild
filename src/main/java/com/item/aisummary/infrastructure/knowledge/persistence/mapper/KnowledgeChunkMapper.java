package com.item.aisummary.infrastructure.knowledge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeChunkEntity;
import org.apache.ibatis.annotations.Mapper;

/** knowledge_chunk 表的 MyBatis Mapper。 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkEntity> {}
