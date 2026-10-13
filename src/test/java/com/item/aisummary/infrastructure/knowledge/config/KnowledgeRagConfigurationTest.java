package com.item.aisummary.infrastructure.knowledge.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.item.aisummary.application.file.content.FileContentService;
import com.item.aisummary.application.knowledge.port.KnowledgeChunkRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeChunker;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentParser;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeVectorIndex;
import com.item.aisummary.application.knowledge.processing.ChunkingMode;
import com.item.aisummary.application.knowledge.processing.FileFormat;
import com.item.aisummary.application.knowledge.processing.KnowledgeProcessingRouter;
import com.item.aisummary.application.knowledge.processing.KnowledgeSourceReader;
import com.item.aisummary.application.knowledge.processing.ParsedDocument;
import com.item.aisummary.application.knowledge.processing.PreparedSource;
import com.item.aisummary.application.knowledge.service.KnowledgeBuildLifecycleService;
import com.item.aisummary.application.knowledge.service.KnowledgeChunkService;
import com.item.aisummary.application.knowledge.service.KnowledgeIndexBuildService;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class KnowledgeRagConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeRagConfiguration.class, TestBeans.class);

    @Test
    void disabledConfigurationDoesNotRequireModelsOrStrategies() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(KnowledgeRagConfiguration.RagRuntimeRequirements.class);
            assertThat(context).doesNotHaveBean("knowledgeIndexBuildService");
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
                @Override public java.util.List<com.item.aisummary.application.knowledge.processing.ChunkGroupDraft>
                        chunk(ParsedDocument document,
                                com.item.aisummary.application.knowledge.processing.ChunkingOptions options) {
                    throw new AssertionError("test strategy should not chunk during startup");
                }
            };
        }
    }
}
