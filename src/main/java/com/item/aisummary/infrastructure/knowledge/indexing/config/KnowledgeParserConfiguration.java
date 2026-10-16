package com.item.aisummary.infrastructure.knowledge.indexing.config;

import com.item.aisummary.infrastructure.knowledge.indexing.parse.docx.DocxKnowledgeDocumentParser;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.markdown.MarkdownKnowledgeDocumentParser;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.txt.TextKnowledgeDocumentParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库文档解析策略条件装配配置类。
 *
 * <p>在 RAG 功能开启（{@code app.knowledge.rag.enabled=true}）时，注册本地 TXT、Markdown 和 DOCX 生产解析策略 Bean。
 * 解析器均为无外部网络/模型调用的本地处理组件，最大源文件预算复用 {@code app.knowledge.rag.max-source-size}。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeRagProperties.class)
@ConditionalOnProperty(prefix = "app.knowledge.rag", name = "enabled", havingValue = "true")
public class KnowledgeParserConfiguration {

    /**
     * 装配纯文本（TXT）文档解析策略 Bean。
     *
     * @param properties RAG 配置属性
     * @return {@link TextKnowledgeDocumentParser} 实例
     */
    @Bean
    TextKnowledgeDocumentParser textKnowledgeDocumentParser(KnowledgeRagProperties properties) {
        return new TextKnowledgeDocumentParser(properties.maxSourceSize().toBytes());
    }

    /**
     * 装配 Markdown 文档解析策略 Bean。
     *
     * @param properties RAG 配置属性
     * @return {@link MarkdownKnowledgeDocumentParser} 实例
     */
    @Bean
    MarkdownKnowledgeDocumentParser markdownKnowledgeDocumentParser(KnowledgeRagProperties properties) {
        return new MarkdownKnowledgeDocumentParser(properties.maxSourceSize().toBytes());
    }

    /**
     * 装配 Office Word（DOCX）文档解析策略 Bean。
     *
     * <p>基于 Apache POI 与 StAX 前置预检，纯本地内存流安全解析，无需云端凭据或外部服务。
     *
     * @param properties RAG 配置属性（注入统一的源文件大小安全预算）
     * @return {@link DocxKnowledgeDocumentParser} 实例
     */
    @Bean
    DocxKnowledgeDocumentParser docxKnowledgeDocumentParser(KnowledgeRagProperties properties) {
        return new DocxKnowledgeDocumentParser(properties.maxSourceSize().toBytes());
    }
}
