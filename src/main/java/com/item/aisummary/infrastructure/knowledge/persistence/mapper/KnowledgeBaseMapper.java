package com.item.aisummary.infrastructure.knowledge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeBaseEntity;
import org.apache.ibatis.annotations.Mapper;

/** knowledge_base 表的 MyBatis Mapper。 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseEntity> {}
