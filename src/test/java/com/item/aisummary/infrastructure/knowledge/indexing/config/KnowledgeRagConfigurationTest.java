package com.item.aisummary.infrastructure.knowledge.indexing.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.item.aisummary.application.file.content.FileContentService;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkGroupDraft;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingMode;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingOptions;
import com.item.aisummary.application.knowledge.indexing.chunk.KnowledgeChunkGroupWriter;
import com.item.aisummary.application.knowledge.indexing.chunk.KnowledgeChunker;
import com.item.aisummary.application.knowledge.indexing.parse.KnowledgeDocumentParser;
import com.item.aisummary.application.knowledge.port.KnowledgeChunkRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeBuildLifecycleService;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeIndexBuildService;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeProcessingRouter;
import com.item.aisummary.application.knowledge.indexing.parse.ParsedDocument;
import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.application.knowledge.indexing.source.KnowledgeSourceReader;
import com.item.aisummary.application.knowledge.indexing.source.PreparedSource;
import com.item.aisummary.application.knowledge.indexing.vector.KnowledgeVectorIndex;
import com.item.aisummary.application.knowledge.service.KnowledgeChunkService;
import com.item.aisummary.infrastructure.knowledge.indexing.config.KnowledgeRagConfiguration;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 条件装配（KnowledgeRagConfiguration）单元测试。
 *
 * <p>基于 ApplicationContextRunner 验证条件装配的前置断言边界：
 * <ul>
 *   <li>RAG 关闭时不依赖任何 AI 模型或策略 Bean。</li>
 *   <li>开启但缺少格式解析策略或缺少 FLAT 分块策略时拒绝启动。</li>
 *   <li>开启但缺少 EmbeddingModel 或 VectorStore 时拒绝启动。</li>
 *   <li>依赖齐全时成功装配全套内部构建 Bean 拓扑。</li>
 * </ul>
 */
class KnowledgeRagConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeRagConfiguration.class, TestBeans.class);

    @Test
    void disabledConfigurationDoesNotRequireModelsOrStrategies() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(KnowledgeRagConfiguration.RagRuntimeRequirements.class);
            assertThat(context).doesNotHaveBean("knowledgeIndexBuildService");
            assertThat(context).doesNotHaveBean(KnowledgeChunkGroupWriter.class);
        });
    }

    @Test
    void enabledConfigurationRejectsMissingParserOrFlatChunker() {
        runner.withPropertyValues(
                        "app.knowledge.rag.enabled=true",
                        "spring.ai.model.embedding=openai",
                        "spring.ai.vectorstore.type=qdrant")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("必须注册至少一个文档解析策略");
                });
    }

    @Test
    void enabledConfigurationRejectsMissingFlatChunker() {
        runner.withUserConfiguration(ParserOnlyBeans.class)
                .withPropertyValues(
                        "app.knowledge.rag.enabled=true",
                        "spring.ai.model.embedding=openai",
                        "spring.ai.vectorstore.type=qdrant")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("必须注册 FLAT 分块策略");
                });
    }

    @Test
    void enabledConfigurationRejectsMissingVectorStore() {
        runner.withUserConfiguration(StrategyBeans.class, EmbeddingOnlyBeans.class)
                .withPropertyValues(
                        "app.knowledge.rag.enabled=true",
                        "spring.ai.model.embedding=openai",
                        "spring.ai.vectorstore.type=qdrant")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("缺少 VectorStore 适配器");
                });
    }

    @Test
    void enabledConfigurationAssemblesPipelineFromTestDoublesOnly() {
        runner.withUserConfiguration(StrategyBeans.class, RuntimeBeans.class)
                .withPropertyValues(
                        "app.knowledge.rag.enabled=true",
                        "spring.ai.model.embedding=openai",
                        "spring.ai.vectorstore.type=qdrant")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(KnowledgeProcessingRouter.class);
                    assertThat(context).hasSingleBean(KnowledgeSourceReader.class);
                    assertThat(context).hasSingleBean(KnowledgeBuildLifecycleService.class);
                    assertThat(context).hasSingleBean(KnowledgeChunkGroupWriter.class);
                    assertThat(context).hasSingleBean(KnowledgeVectorIndex.class);
                    assertThat(context).hasSingleBean(KnowledgeIndexBuildService.class);
                });
    }

    @Test
    void enabledConfigurationRejectsMissingEmbeddingOrVectorStoreWithoutCallingExternalServices() {
        runner.withUserConfiguration(StrategyBeans.class)
                .withPropertyValues(
                        "app.knowledge.rag.enabled=true",
                        "spring.ai.model.embedding=openai",
                        "spring.ai.vectorstore.type=qdrant")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("缺少 EmbeddingModel 适配器");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestBeans {}

    @Configuration(proxyBeanMethods = false)
    static class ParserOnlyBeans {
        @Bean
        KnowledgeDocumentParser parser() {
            return new KnowledgeDocumentParser() {
                @Override public FileFormat format() { return FileFormat.TXT; }
                @Override public ParsedDocument parse(PreparedSource source) {
                    throw new AssertionError("test strategy should not parse during startup");
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EmbeddingOnlyBeans {
        @Bean
        EmbeddingModel embeddingModel() {
            return org.mockito.Mockito.mock(EmbeddingModel.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RuntimeBeans {
        @Bean
        FileContentService fileContentService() {
            return org.mockito.Mockito.mock(FileContentService.class);
        }

        @Bean
        KnowledgeDocumentRepository knowledgeDocumentRepository() {
            return org.mockito.Mockito.mock(KnowledgeDocumentRepository.class);
        }
        @Bean
        KnowledgeRevisionRepository knowledgeRevisionRepository() {
            return org.mockito.Mockito.mock(KnowledgeRevisionRepository.class);
        }
        @Bean
        KnowledgeChunkRepository knowledgeChunkRepository() {
            return org.mockito.Mockito.mock(KnowledgeChunkRepository.class);
        }
        @Bean
        KnowledgeChunkService knowledgeChunkService() {
            return org.mockito.Mockito.mock(KnowledgeChunkService.class);
        }
        @Bean
        VectorStore vectorStore() {
            return org.mockito.Mockito.mock(VectorStore.class);
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return org.mockito.Mockito.mock(EmbeddingModel.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StrategyBeans {
        @Bean
        KnowledgeDocumentParser parser() {
            return new KnowledgeDocumentParser() {
                @Override public FileFormat format() { return FileFormat.TXT; }
                @Override public ParsedDocument parse(PreparedSource source) {
                    throw new AssertionError("test strategy should not parse during startup");
                }
            };
        }

        @Bean
        KnowledgeChunker flatChunker() {
            return new KnowledgeChunker() {
                @Override public ChunkingMode mode() { return ChunkingMode.FLAT; }
                @Override public java.util.List<ChunkGroupDraft>
                        chunk(ParsedDocument document, ChunkingOptions options) {
                    throw new AssertionError("test strategy should not chunk during startup");
                }
            };
        }
    }
}
