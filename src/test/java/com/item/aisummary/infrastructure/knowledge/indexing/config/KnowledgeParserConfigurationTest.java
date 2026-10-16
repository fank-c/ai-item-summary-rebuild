package com.item.aisummary.infrastructure.knowledge.indexing.config;

import static com.item.aisummary.infrastructure.knowledge.indexing.parse.support.ParserTestSources.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.item.aisummary.application.knowledge.indexing.build.KnowledgeIndexBuildService;
import com.item.aisummary.application.knowledge.indexing.build.KnowledgeProcessingRouter;
import com.item.aisummary.application.knowledge.indexing.chunk.ChunkingMode;
import com.item.aisummary.application.knowledge.indexing.chunk.KnowledgeChunker;
import com.item.aisummary.application.knowledge.indexing.parse.KnowledgeDocumentParser;
import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.docx.DocxKnowledgeDocumentParser;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.markdown.MarkdownKnowledgeDocumentParser;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.txt.TextKnowledgeDocumentParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库文档解析器（TXT、MD、DOCX）条件配置集成测试。
 *
 * <p>验证目标：
 * <ul>
 *   <li>RAG 关闭时：不注册任何文档解析器 Bean；</li>
 *   <li>RAG 开启时：自动注册本地纯内存解析器（Text、Markdown、Docx），注入统一源文件大小预算；</li>
 *   <li>路由与装配：通过路由组件验证已注册解析器支持的格式与行为。</li>
 * </ul>
 */
class KnowledgeParserConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeParserConfiguration.class);

    @Test
    void disabledConfigurationDoesNotRegisterParsersOrRequireCredentials() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(KnowledgeDocumentParser.class);
        });
        runner.withPropertyValues("app.knowledge.rag.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(KnowledgeDocumentParser.class);
        });
    }

    @Test
    void enabledConfigurationRegistersRealLocalParsersAndRoutesOnlySupportedFormats() {
        runner.withPropertyValues("app.knowledge.rag.enabled=true", "app.knowledge.rag.max-source-size=12B")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(TextKnowledgeDocumentParser.class)
                            .hasSingleBean(MarkdownKnowledgeDocumentParser.class)
                            .hasSingleBean(DocxKnowledgeDocumentParser.class);
                    var router = new KnowledgeProcessingRouter(List.copyOf(context.getBeansOfType(KnowledgeDocumentParser.class).values()), List.of());
                    assertThat(router.requireParser(FileFormat.TXT).parse(source("正文", FileFormat.TXT)).blocks()).hasSize(1);
                    assertThat(router.requireParser(FileFormat.MD).parse(source("# 标题", FileFormat.MD)).blocks()).hasSize(1);
                    assertThatIllegalArgumentException().isThrownBy(() -> router.requireParser(FileFormat.TXT)
                            .parse(source("1234567890123", FileFormat.TXT)));
                    assertThatIllegalArgumentException().isThrownBy(() -> router.requireParser(FileFormat.MD)
                            .parse(source("1234567890123", FileFormat.MD)));
                    assertThat(router.requireParser(FileFormat.DOCX)).isInstanceOf(DocxKnowledgeDocumentParser.class);
                    assertThatIllegalStateException().isThrownBy(() -> router.requireParser(FileFormat.PDF));
                });
    }

    @Test
    void realParsersDoNotMakePipelineStartWithoutFlatChunker() {
        runner.withUserConfiguration(KnowledgeRagConfiguration.class)
                .withPropertyValues("app.knowledge.rag.enabled=true", "spring.ai.model.embedding=openai", "spring.ai.vectorstore.type=qdrant")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("必须注册 FLAT 分块策略");
                });
    }

    @Test
    void realParsersComposeWithExistingPipelineUsingOnlyBackendAndChunkerDoubles() {
        runner.withUserConfiguration(KnowledgeRagConfiguration.class,
                        KnowledgeRagConfigurationTest.RuntimeBeans.class, ChunkerBeans.class)
                .withPropertyValues("app.knowledge.rag.enabled=true", "spring.ai.model.embedding=openai", "spring.ai.vectorstore.type=qdrant")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(KnowledgeIndexBuildService.class);
                    assertThat(context.getBean(KnowledgeProcessingRouter.class).requireParser(FileFormat.MD))
                            .isInstanceOf(MarkdownKnowledgeDocumentParser.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class ChunkerBeans {
        @Bean
        KnowledgeChunker flatChunker() {
            var chunker = mock(KnowledgeChunker.class);
            when(chunker.mode()).thenReturn(ChunkingMode.FLAT);
            return chunker;
        }
    }
}
