package com.item.aisummary.infrastructure.knowledge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeBaseEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库表（knowledge_base）的 MyBatis-Plus Mapper 接口。
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseEntity> {}
