package com.item.aisummary.infrastructure.knowledge.indexing.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 配置属性（KnowledgeRagProperties）绑定与范围校验单元测试。
 *
 * <p>验证属性默认安全值绑定、配置项覆盖映射及非法边界值校验（如 minChunkLengthToEmbed != 0、maxSourceSize 超限）。
 */
class KnowledgeRagPropertiesTest {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsSafeDefaultsWithoutEnablingRag() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            KnowledgeRagProperties properties = context.getBean(KnowledgeRagProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.batchSize()).isEqualTo(128);
            assertThat(properties.chunkSize()).isEqualTo(512);
            assertThat(properties.parentChunkSize()).isEqualTo(2048);
            assertThat(properties.chunkOverlap()).isZero();
            assertThat(properties.minChunkSizeChars()).isEqualTo(200);
            assertThat(properties.minChunkLengthToEmbed()).isZero();
            assertThat(properties.maxNumChunks()).isEqualTo(100_000);
            assertThat(properties.keepSeparator()).isTrue();
            assertThat(properties.punctuationMarks()).isEqualTo(".?!;。？！；\n");
            assertThat(properties.maxSourceSize().toBytes()).isEqualTo(20L * 1024 * 1024);
            assertThat(properties.chunkingOptions().parentChunkSize()).isEqualTo(2048);
            assertThat(properties.chunkingOptions().chunkOverlap()).isZero();
        });
    }

    @Test
    void bindsOverridesAndCreatesChunkingOptions() {
        contextRunner
                .withPropertyValues(
                        "app.knowledge.rag.enabled=true",
                        "app.knowledge.rag.batch-size=16",
                        "app.knowledge.rag.chunk-size=256",
                        "app.knowledge.rag.parent-chunk-size=1024",
                        "app.knowledge.rag.chunk-overlap=64",
                        "app.knowledge.rag.min-chunk-size-chars=32",
                        "app.knowledge.rag.min-chunk-length-to-embed=0",
                        "app.knowledge.rag.max-num-chunks=900",
                        "app.knowledge.rag.keep-separator=false",
                        "app.knowledge.rag.punctuation-marks=!?",
                        "app.knowledge.rag.max-source-size=4MB")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    KnowledgeRagProperties properties = context.getBean(KnowledgeRagProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.chunkSize()).isEqualTo(256);
                    assertThat(properties.parentChunkSize()).isEqualTo(1024);
                    assertThat(properties.chunkOverlap()).isEqualTo(64);
                    assertThat(properties.maxSourceSize().toBytes()).isEqualTo(4L * 1024 * 1024);
                    assertThat(properties.chunkingOptions())
                            .usingRecursiveComparison()
                            .isEqualTo(new com.item.aisummary.application.knowledge.indexing.chunk.ChunkingOptions(
                                    256, 32, 0, 900, false, "!?", 1024, 64));
                });
    }

    @Test
    void rejectsUnsupportedChunkLengthThresholdAndOversizedSourceLimit() {
        contextRunner.withPropertyValues("app.knowledge.rag.min-chunk-length-to-embed=1")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("app.knowledge.rag.max-source-size=2GB")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsInvalidParentChunkSizeAndOverlap() {
        contextRunner.withPropertyValues(
                        "app.knowledge.rag.chunk-size=512",
                        "app.knowledge.rag.parent-chunk-size=256")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues(
                        "app.knowledge.rag.chunk-size=512",
                        "app.knowledge.rag.chunk-overlap=512")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("app.knowledge.rag.chunk-overlap=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KnowledgeRagProperties.class)
    static class TestConfiguration {}
}
