package com.item.aisummary.infrastructure.knowledge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeChunkEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识片段表（knowledge_chunk）的 MyBatis-Plus Mapper 接口。
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkEntity> {}
