package com.item.aisummary.web.knowledge.response;

import com.item.aisummary.domain.knowledge.model.KnowledgeBase;
import java.time.Instant;

/**
 * 知识库元数据信息的 HTTP 响应记录。
 *
 * <p>用于对外暴露知识库的基础元数据，包含知识库标识、展示名称、可用状态及时间戳，
 * 不包含所有者用户标识或权限访问控制关系。
 *
 * @param id 知识库业务 ID，32 位全局唯一业务标识
 * @param name 知识库展示名称，最多 100 个字符，允许多个知识库同名
 * @param status 知识库业务可用状态，当前 1 表示正常可用
 * @param createAt 知识库创建时间，按 UTC 微秒精度记录
 * @param updateAt 知识库最后更新时间，按 UTC 微秒精度记录
 */
public record KnowledgeBaseResponse(
        String id,
        String name,
        int status,
        Instant createAt,
        Instant updateAt) {

    /**
     * 将知识库领域实体转换为 HTTP 响应记录。
     *
     * @param base 知识库领域实体
     * @return 映射后的知识库 HTTP 响应记录
     */
    public static KnowledgeBaseResponse from(KnowledgeBase base) {
        return new KnowledgeBaseResponse(
                base.id(),
                base.name(),
                base.status(),
                base.createAt(),
                base.updateAt());
    }
}
