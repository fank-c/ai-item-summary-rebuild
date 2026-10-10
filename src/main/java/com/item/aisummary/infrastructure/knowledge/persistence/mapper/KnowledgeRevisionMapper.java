package com.item.aisummary.infrastructure.knowledge.persistence.mapper;

import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeRevisionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeRevisionMapper extends BaseMapper<KnowledgeRevisionEntity> {
    @Select("SELECT COALESCE(MAX(revision_number), 0) FROM knowledge_document_revision WHERE knowledge_document_id = #{documentId}")
    int findMaxRevisionNumber(@Param("documentId") String documentId);

    /** 只取 ID 避免自定义查询绕过 Instant 的自动结果映射；事务随后读取完整记录。 */
    @Select("SELECT id FROM knowledge_document_revision WHERE id = #{id} FOR UPDATE")
    String lockById(@Param("id") String id);
}
