package com.item.aisummary.infrastructure.knowledge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeRevisionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 文档版本表（knowledge_document_revision）的 MyBatis-Plus Mapper 接口。
 */
@Mapper
public interface KnowledgeRevisionMapper extends BaseMapper<KnowledgeRevisionEntity> {
    /**
     * 查询指定文档当前已存在的最大版本号，若尚无版本则返回 0。
     *
     * @param documentId 知识文档 ID
     * @return 当前最大版本号，无版本记录时返回 0
     */
    @Select(
            "SELECT COALESCE(MAX(revision_number), 0) "
                    + "FROM knowledge_document_revision "
                    + "WHERE knowledge_document_id = #{documentId}")
    int findMaxRevisionNumber(@Param("documentId") String documentId);

    /**
     * 对指定版本行加排他锁（FOR UPDATE）。
     *
     * <p>只取 ID 避免自定义查询绕过 Instant 自动映射，事务随后读取完整记录。
     *
     * @param id 版本业务 ID
     * @return 锁定的版本业务 ID，若记录不存在则返回 null
     */
    @Select("SELECT id FROM knowledge_document_revision WHERE id = #{id} FOR UPDATE")
    String lockById(@Param("id") String id);
}
