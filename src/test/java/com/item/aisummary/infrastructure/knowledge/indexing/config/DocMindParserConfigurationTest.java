package com.item.aisummary.infrastructure.knowledge.indexing.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.item.aisummary.application.knowledge.indexing.parse.KnowledgeDocumentParser;
import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.docx.DocxKnowledgeDocumentParser;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.pdf.AlibabaPdfKnowledgeDocumentParser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 阿里云文档智能解析器条件装配集成测试。
 *
 * <p>验证目标：
 * <ul>
 *   <li>RAG 关闭时：即使 docmind.enabled=true 也不加载凭据或装配解析器；</li>
 *   <li>RAG 开启且 docmind 默认关闭：自动注册本地 TXT、MD、DOCX 解析器，不要求云凭据；</li>
 *   <li>RAG 开启且 docmind 显式开启：缺少 AccessKey 时阻断启动拒绝运行，配置完整凭据时正确注册 PDF 路由。</li>
 * </ul>
 */
class DocMindParserConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeParserConfiguration.class, DocMindParserConfiguration.class);

    @Test
    void disabledRagDoesNotLoadDocMindCredentialsOrRegisterParsers() {
        runner.withPropertyValues("app.knowledge.rag.enabled=false", "app.knowledge.rag.docmind.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AlibabaPdfKnowledgeDocumentParser.class);
                    assertThat(context).doesNotHaveBean(DocMindProperties.class);
                });
    }

    @Test
    void enabledRagRegistersLocalDocxButDoesNotRequireCloudCredentialsByDefault() {
        runner.withPropertyValues("app.knowledge.rag.enabled=true", "app.knowledge.rag.docmind.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DocxKnowledgeDocumentParser.class);
                    assertThat(context).doesNotHaveBean(AlibabaPdfKnowledgeDocumentParser.class);
                    assertThat(context.getBeansOfType(KnowledgeDocumentParser.class).values())
                            .extracting(KnowledgeDocumentParser::format)
                            .containsExactlyInAnyOrder(FileFormat.TXT, FileFormat.MD, FileFormat.DOCX);
                });
    }

    @Test
    void explicitlyEnabledCloudParserRequiresCredentials() {
        runner.withPropertyValues("app.knowledge.rag.enabled=true", "app.knowledge.rag.docmind.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("必须配置 AccessKey ID 和 Secret");
                });
    }

    @Test
    void enabledCloudParserRegistersPdfRouteWithCredentials() {
        runner.withPropertyValues(
                        "app.knowledge.rag.enabled=true",
                        "app.knowledge.rag.docmind.enabled=true",
                        "app.knowledge.rag.docmind.access-key-id=test-key",
                        "app.knowledge.rag.docmind.access-key-secret=test-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AlibabaPdfKnowledgeDocumentParser.class);
                    assertThat(context.getBeansOfType(KnowledgeDocumentParser.class).values())
                            .extracting(KnowledgeDocumentParser::format)
                            .containsExactlyInAnyOrder(FileFormat.TXT, FileFormat.MD, FileFormat.DOCX, FileFormat.PDF);
                });
    }
}
