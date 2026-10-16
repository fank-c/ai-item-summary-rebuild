package com.item.aisummary.infrastructure.knowledge.indexing.config;

import com.item.aisummary.infrastructure.knowledge.indexing.parse.pdf.AlibabaPdfKnowledgeDocumentParser;
import com.item.aisummary.infrastructure.knowledge.indexing.parse.pdf.DocMindPdfParserClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云文档智能（Document Mind）PDF 云端解析策略条件配置。
 *
 * <p>装配原则与费用控制：
 * <ul>
 *   <li>双层条件装配：仅在 {@code app.knowledge.rag.enabled=true} 且 {@code app.knowledge.rag.docmind.enabled=true} 时激活。</li>
 *   <li>无静默调用：未显式开启时绝不注册 PDF 解析策略，也不会向阿里云发送任何网络请求或产生云端解析计费。</li>
 *   <li>本地大小预算对齐：解析器的最大源大小预算直接注入自 {@link KnowledgeRagProperties#maxSourceSize()}，确保与其他解析器具有统一的安全输入防御上限。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DocMindProperties.class)
@ConditionalOnProperty(prefix = "app.knowledge.rag", name = "enabled", havingValue = "true")
public class DocMindParserConfiguration {

    /**
     * 创建 DocMind 云端 API 同步客户端适配器 Bean。
     *
     * @param properties DocMind 接入属性（包含密钥、端点及超时参数）
     * @return 用于提交、轮询与分页拉取解析结果的客户端实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.knowledge.rag.docmind", name = "enabled", havingValue = "true")
    DocMindPdfParserClient docMindPdfParserClient(DocMindProperties properties) {
        return new DocMindPdfParserClient(properties);
    }

    /**
     * 创建结合本地 PDFBox 容器预检与云端 DocMind OCR 解析的 PDF 生产解析策略 Bean。
     *
     * @param ragProperties RAG 全局构建属性（用于获取源文件大小上限）
     * @param client DocMind 云端 API 客户端适配器
     * @return 注册到解析器路由中的 PDF 解析策略实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.knowledge.rag.docmind", name = "enabled", havingValue = "true")
    AlibabaPdfKnowledgeDocumentParser alibabaPdfKnowledgeDocumentParser(
            KnowledgeRagProperties ragProperties, DocMindPdfParserClient client) {
        return new AlibabaPdfKnowledgeDocumentParser(ragProperties.maxSourceSize().toBytes(), client);
    }
}
