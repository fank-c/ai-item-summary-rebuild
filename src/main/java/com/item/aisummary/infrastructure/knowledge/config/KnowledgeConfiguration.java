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

/**
 * 知识库基础服务装配配置。
 *
 * <p>装配知识库元数据、文档登记、版本及片段管理等基础 Bean（独立于 RAG 开关）。
 */
@Configuration(proxyBeanMethods = false)
public class KnowledgeConfiguration {

    /**
     * 装配知识库管理服务 Bean。
     *
     * @param bases 知识库仓储组件
     * @param clock 共享微秒时钟源
     * @return {@link KnowledgeBaseService} 实例
     */
    @Bean
    KnowledgeBaseService knowledgeBaseService(KnowledgeBaseRepository bases, Clock clock) {
        return new KnowledgeBaseService(bases, clock);
    }

    /**
     * 装配知识文档登记服务 Bean。
     *
     * @param bases 知识库仓储组件
     * @param documents 知识文档仓储组件
     * @param files 文件内容与所有权校验服务
     * @param clock 共享微秒时钟源
     * @return {@link KnowledgeDocumentService} 实例
     */
    @Bean
    KnowledgeDocumentService knowledgeDocumentService(
            KnowledgeBaseRepository bases,
            KnowledgeDocumentRepository documents,
            FileContentService files,
            Clock clock) {
        return new KnowledgeDocumentService(bases, documents, files, clock);
    }

    /**
     * 装配知识文档版本管理基础服务 Bean。
     *
     * @param documents 知识文档仓储组件
     * @param revisions 知识文档版本仓储组件
     * @param clock 共享微秒时钟源
     * @return {@link KnowledgeRevisionService} 实例
     */
    @Bean
    KnowledgeRevisionService knowledgeRevisionService(
            KnowledgeDocumentRepository documents,
            KnowledgeRevisionRepository revisions,
            Clock clock) {
        return new KnowledgeRevisionService(documents, revisions, clock);
    }

    /**
     * 装配知识片段批次管理服务 Bean。
     *
     * @param revisions 知识文档版本仓储组件
     * @param chunks 知识片段仓储组件
     * @param clock 共享微秒时钟源
     * @return {@link KnowledgeChunkService} 实例
     */
    @Bean
    KnowledgeChunkService knowledgeChunkService(
            KnowledgeRevisionRepository revisions, KnowledgeChunkRepository chunks, Clock clock) {
        return new KnowledgeChunkService(revisions, chunks, clock);
    }
}
