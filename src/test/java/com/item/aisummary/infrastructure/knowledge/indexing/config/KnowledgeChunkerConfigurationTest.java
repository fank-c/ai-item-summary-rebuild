package com.item.aisummary.infrastructure.knowledge.indexing.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.item.aisummary.application.knowledge.indexing.build.KnowledgeIndexBuildService;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeProcessingRouter;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingMode;
import com.item.aisummary.application.knowledge.indexing.chunk.KnowledgeChunker;
import com.item.aisummary.infrastructure.knowledge.indexing.chunk.TokenBudgetKnowledgeChunker;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KnowledgeChunkerConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeChunkerConfiguration.class);

    @Test
    void disabledConfigurationDoesNotRegisterChunkers() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(KnowledgeChunker.class);
        });
        runner.withPropertyValues("app.knowledge.rag.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(KnowledgeChunker.class);
        });
    }

    @Test
    void enabledConfigurationRegistersBothChunkingModes() {
        runner.withPropertyValues("app.knowledge.rag.enabled=true").run(context -> {
            assertThat(context).hasNotFailed()
                    .hasBean("flatKnowledgeChunker")
                    .hasBean("parentChildKnowledgeChunker");

            List<KnowledgeChunker> chunkers = List.copyOf(context.getBeansOfType(KnowledgeChunker.class).values());
            assertThat(chunkers).hasSize(2);

            var router = new KnowledgeProcessingRouter(List.of(), chunkers);
            assertThat(router.requireChunker(ChunkingMode.FLAT).mode()).isEqualTo(ChunkingMode.FLAT);
            assertThat(router.requireChunker(ChunkingMode.PARENT_CHILD).mode()).isEqualTo(ChunkingMode.PARENT_CHILD);
        });
    }

    @Test
    void enabledConfigurationWithoutBackendFailsStartup() {
        runner.withUserConfiguration(KnowledgeParserConfiguration.class, KnowledgeRagConfiguration.class)
                .withPropertyValues(
                        "app.knowledge.rag.enabled=true",
                        "spring.ai.model.embedding=openai",
                        "spring.ai.vectorstore.type=qdrant")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("缺少 EmbeddingModel 适配器");
                });
    }

    @Test
    void fullConfigurationAssemblesWithBackendAndRepositoryDoubles() {
        runner.withUserConfiguration(
                        KnowledgeParserConfiguration.class,
                        KnowledgeRagConfiguration.class,
                        KnowledgeRagConfigurationTest.RuntimeBeans.class)
                .withPropertyValues(
                        "app.knowledge.rag.enabled=true",
                        "spring.ai.model.embedding=openai",
                        "spring.ai.vectorstore.type=qdrant")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(KnowledgeIndexBuildService.class);
                    var router = context.getBean(KnowledgeProcessingRouter.class);
                    assertThat(router.requireChunker(ChunkingMode.FLAT))
                            .isInstanceOf(TokenBudgetKnowledgeChunker.class);
                    assertThat(router.requireChunker(ChunkingMode.PARENT_CHILD))
                            .isInstanceOf(TokenBudgetKnowledgeChunker.class);
                });
    }
}
