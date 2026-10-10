package com.item.aisummary.application.knowledge.port;

import com.item.aisummary.domain.knowledge.model.KnowledgeDocument;
import java.util.List;
import java.util.Optional;

/** 知识文档持久化能力。 */
public interface KnowledgeDocumentRepository {
    /**
     * 插入知识库文档记录。
     *
     * <p>若同一知识库已存在相同源文件，抛出 DOCUMENT_ALREADY_REGISTERED 业务冲突。
     *
     * @param document 待保存的知识文档对象
     * @return 保存成功后的知识文档对象
     */
    KnowledgeDocument insert(KnowledgeDocument document);

    /**
     * 根据 ID 查询知识文档。
     *
     * @param id 文档唯一标识
     * @return 包含文档的 Optional，不存在时为空
     */
    Optional<KnowledgeDocument> findById(String id);

    /**
     * 根据知识库 ID 和源文件 ID 查询登记记录。
     *
     * @param knowledgeBaseId 知识库唯一标识
     * @param fileId 源文件唯一标识
     * @return 包含文档的 Optional，未登记时为空
     */
    Optional<KnowledgeDocument> findByKnowledgeBaseAndFile(String knowledgeBaseId, String fileId);

    /**
     * 查询指定知识库下的所有文档列表。
     *
     * @param knowledgeBaseId 知识库唯一标识
     * @return 文档列表；无记录时返回空列表
     */
    List<KnowledgeDocument> findByKnowledgeBaseId(String knowledgeBaseId);
}
