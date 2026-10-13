package com.item.aisummary.infrastructure.knowledge.config;

import com.item.aisummary.application.file.content.FileContentService;
import com.item.aisummary.application.knowledge.port.KnowledgeChunkRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeChunker;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentParser;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeVectorIndex;
import com.item.aisummary.application.knowledge.processing.ChunkingMode;
import com.item.aisummary.application.knowledge.processing.KnowledgeProcessingRouter;
import com.item.aisummary.application.knowledge.processing.KnowledgeSourceReader;
import com.item.aisummary.application.knowledge.service.KnowledgeBuildLifecycleService;
import com.item.aisummary.application.knowledge.service.KnowledgeChunkService;
import com.item.aisummary.application.knowledge.service.KnowledgeIndexBuildService;
import com.item.aisummary.infrastructure.knowledge.ai.SpringAiKnowledgeVectorIndex;
import java.time.Clock;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 知识库 RAG 内部构建链路的条件装配配置类。
 *
 * <p>只有当配置属性 {@code app.knowledge.rag.enabled=true} 时才会激活本配置：
 * <ul>
 *   <li>开启时必须满足显式运行要求：环境变量配置 {@code spring.ai.model.embedding=openai}、
 *       {@code spring.ai.vectorstore.type=qdrant}，且环境中已注册至少一个文档解析策略（{@link KnowledgeDocumentParser}）、
 *       必须注册 FLAT 分块策略（{@link KnowledgeChunker}），同时可用 {@link EmbeddingModel} 与 {@link VectorStore} 适配器存在。
 *       若缺少任何一项，应用将在启动上下文刷新阶段抛出异常明确拒绝启动，避免装配看似可用实则无策略的空骨架。</li>
 *   <li>关闭时不装配任何构建编排 Bean、向量适配 Bean 或运行时检查，健康检查与用户、文件及基础知识库元数据服务仍正常可用。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeRagProperties.class)
@ConditionalOnProperty(prefix = "app.knowledge.rag", name = "enabled", havingValue = "true")
public class KnowledgeRagConfiguration {

    /**
     * RAG 开启时的运行时前置依赖校验 Bean。
     *
     * <p>以不透明标记对象作为下游 Bean 的依赖输入，保证所有 RAG 基础设施和编排 Bean 实例化前
     * 必须先完成环境配置与真实策略的存在性断言。
     *
     * @param environment Spring 运行环境
     * @param parsers 已注册的文档解析策略列表
     * @param chunkers 已注册的内容切分策略列表
     * @param embeddingModels EmbeddingModel 提供器
     * @param vectorStores VectorStore 提供器
     * @return 校验通过的不透明标记对象
     * @throws IllegalStateException 当缺少必要配置、适配器或必要策略时抛出
     */
    @Bean
    RagRuntimeRequirements knowledgeRagRuntimeRequirements(
            Environment environment,
            List<KnowledgeDocumentParser> parsers,
            List<KnowledgeChunker> chunkers,
            ObjectProvider<EmbeddingModel> embeddingModels,
            ObjectProvider<VectorStore> vectorStores) {
        String embedding = environment.getProperty("spring.ai.model.embedding", "none");
        if (!"openai".equalsIgnoreCase(embedding)) {
            throw new IllegalStateException("RAG 开启时必须将 spring.ai.model.embedding 配置为 openai");
        }
        String vectorStore = environment.getProperty("spring.ai.vectorstore.type", "none");
        if (!"qdrant".equalsIgnoreCase(vectorStore)) {
            throw new IllegalStateException("RAG 开启时必须将 spring.ai.vectorstore.type 配置为 qdrant");
        }
        if (parsers.isEmpty()) {
            throw new IllegalStateException("RAG 开启时必须注册至少一个文档解析策略");
        }
        if (chunkers.stream().noneMatch(chunker -> chunker.mode() == ChunkingMode.FLAT)) {
            throw new IllegalStateException("RAG 开启时必须注册 FLAT 分块策略");
        }
        if (embeddingModels.getIfAvailable() == null) {
            throw new IllegalStateException("RAG 开启时缺少 EmbeddingModel 适配器");
        }
        if (vectorStores.getIfAvailable() == null) {
            throw new IllegalStateException("RAG 开启时缺少 VectorStore 适配器");
        }
        return new RagRuntimeRequirements();
    }

    /**
     * 装配文档解析与内容切分策略路由注册表 Bean。
     */
    @Bean
    KnowledgeProcessingRouter knowledgeProcessingRouter(
            List<KnowledgeDocumentParser> parsers,
            List<KnowledgeChunker> chunkers,
            RagRuntimeRequirements requirements) {
        return new KnowledgeProcessingRouter(parsers, chunkers);
    }

    /**
     * 装配源文件原始字节受控读取器 Bean。
     */
    @Bean
    KnowledgeSourceReader knowledgeSourceReader(KnowledgeRagProperties properties) {
        return new KnowledgeSourceReader(properties.maxSourceSize().toBytes());
    }

    /**
     * 装配知识库构建生命周期短事务服务 Bean。
     */
    @Bean
    KnowledgeBuildLifecycleService knowledgeBuildLifecycleService(
            KnowledgeDocumentRepository documents,
            KnowledgeRevisionRepository revisions,
            KnowledgeChunkRepository chunks,
            Clock clock,
            RagRuntimeRequirements requirements) {
        return new KnowledgeBuildLifecycleService(documents, revisions, chunks, clock);
    }

    /**
     * 装配基于 Spring AI VectorStore 的向量索引客户端适配器 Bean。
     */
    @Bean
    KnowledgeVectorIndex knowledgeVectorIndex(
            VectorStore vectorStore, RagRuntimeRequirements requirements) {
        return new SpringAiKnowledgeVectorIndex(vectorStore);
    }

    /**
     * 装配知识库构建核心编排服务 Bean。
     */
    @Bean
    KnowledgeIndexBuildService knowledgeIndexBuildService(
            FileContentService files,
            KnowledgeDocumentRepository documents,
            KnowledgeProcessingRouter router,
            KnowledgeSourceReader sourceReader,
            KnowledgeChunkService chunks,
            KnowledgeVectorIndex vectorIndex,
            KnowledgeBuildLifecycleService lifecycle,
            KnowledgeRagProperties properties,
            RagRuntimeRequirements requirements) {
        return new KnowledgeIndexBuildService(
                files,
                documents,
                router,
                sourceReader,
                chunks,
                vectorIndex,
                lifecycle,
                properties.chunkingOptions(),
                properties.batchSize());
    }

    /**
     * 不透明的前置依赖标记类型，用于强制 Spring 容器在初始化其它 RAG Bean 之前完成运行时检查。
     */
    static final class RagRuntimeRequirements {}
}
