package com.item.aisummary.infrastructure.knowledge.indexing.config;

import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingMode;
import com.item.aisummary.infrastructure.knowledge.indexing.chunk.TokenBudgetKnowledgeChunker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库内容分块策略条件装配配置类。
 *
 * <p>当 {@code app.knowledge.rag.enabled=true} 时，分别注册 FLAT 与 PARENT_CHILD
 * 两种模式的生产切分策略实例（{@link TokenBudgetKnowledgeChunker}）。
 * 切分策略在构造时不依赖具体参数配置，而是在执行 {@code chunk} 时消费传入的 {@code ChunkingOptions}。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.knowledge.rag", name = "enabled", havingValue = "true")
public class KnowledgeChunkerConfiguration {

    /**
     * 装配平铺模式（FLAT）的 Token 预算分块策略 Bean。
     *
     * @return FLAT 模式切分策略实例
     */
    @Bean
    TokenBudgetKnowledgeChunker flatKnowledgeChunker() {
        return new TokenBudgetKnowledgeChunker(ChunkingMode.FLAT);
    }

    /**
     * 装配父子模式（PARENT_CHILD）的 Token 预算分块策略 Bean。
     *
     * @return PARENT_CHILD 模式切分策略实例
     */
    @Bean
    TokenBudgetKnowledgeChunker parentChildKnowledgeChunker() {
        return new TokenBudgetKnowledgeChunker(ChunkingMode.PARENT_CHILD);
    }
}
