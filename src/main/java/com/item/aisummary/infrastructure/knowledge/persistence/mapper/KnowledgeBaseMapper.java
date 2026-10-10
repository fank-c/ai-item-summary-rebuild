package com.item.aisummary.infrastructure.knowledge.persistence.mapper;

import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeBaseEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseEntity> { }
