package com.item.aisummary.infrastructure.knowledge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.item.aisummary.infrastructure.knowledge.persistence.entity.KnowledgeDocumentEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 知识文档表（knowledge_document）的 MyBatis-Plus Mapper 接口。
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {

    /**
     * 对指定文档行施加排他行锁（SELECT id ... FOR UPDATE）。
     *
     * <p>加锁顺序规则：文档锁必须始终先于版本锁获取，避免在并发发布或并发失败处理时发生死锁（锁顺序反转）。
     * 仅查询 id 字段，避免自定义 SQL 绕过实体时间戳类型转换器。
     *
     * @param id 文档唯一标识
     * @return 成功锁定的文档 ID；若记录不存在则返回 null
     */
    @Select("SELECT id FROM knowledge_document WHERE id = #{id} FOR UPDATE")
    String lockById(@Param("id") String id);
}
