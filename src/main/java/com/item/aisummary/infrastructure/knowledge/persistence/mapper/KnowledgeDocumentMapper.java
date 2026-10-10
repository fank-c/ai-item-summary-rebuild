package com.item.aisummary.infrastructure.knowledge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeDocumentEntity;
import org.apache.ibatis.annotations.Mapper;

/** knowledge_document 表的 MyBatis Mapper。 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {}
