package com.item.aisummary.infrastructure.knowledge.config;

import com.item.aisummary.application.file.content.FileContentService;
import com.item.aisummary.application.knowledge.port.KnowledgeBaseRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeChunkRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeRevisionRepository;
import com.item.aisummary.application.knowledge.service.KnowledgeBaseService;
import com.item.aisummary.application.knowledge.service.KnowledgeChunkService;
import com.item.aisummary.application.knowledge.service.KnowledgeDocumentService;
import com.item.aisummary.application.knowledge.service.KnowledgeRevisionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

/** 复用工程 UTC 时钟和文件模块合同，不创建模型或存储客户端。 */
@Configuration(proxyBeanMethods = false)
public class KnowledgeConfiguration {
    /** 装配知识库创建、查询和改名用例。 */
    @Bean
    KnowledgeBaseService knowledgeBaseService(KnowledgeBaseRepository bases, Clock clock) {
        return new KnowledgeBaseService(bases, clock);
    }

    /** 装配文档登记用例，复用文件内容归属校验能力。 */
    @Bean
    KnowledgeDocumentService knowledgeDocumentService(
            KnowledgeBaseRepository bases,
            KnowledgeDocumentRepository documents,
            FileContentService files,
            Clock clock) {
        return new KnowledgeDocumentService(bases, documents, files, clock);
    }

    /** 装配文档版本创建与查询用例。 */
    @Bean
    KnowledgeRevisionService knowledgeRevisionService(
            KnowledgeDocumentRepository documents,
            KnowledgeRevisionRepository revisions,
            Clock clock) {
        return new KnowledgeRevisionService(documents, revisions, clock);
    }

    /** 装配片段批量保存用例；事务由服务方法声明。 */
    @Bean
    KnowledgeChunkService knowledgeChunkService(
            KnowledgeRevisionRepository revisions, KnowledgeChunkRepository chunks, Clock clock) {
        return new KnowledgeChunkService(revisions, chunks, clock);
    }
}
