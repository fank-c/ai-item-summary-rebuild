package com.item.aisummary.infrastructure.knowledge.indexing.vector;

import com.item.aisummary.application.knowledge.indexing.vector.KnowledgeVectorEntry;
import com.item.aisummary.application.knowledge.indexing.vector.KnowledgeVectorIndex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * 基于 Spring AI 的向量索引客户端适配器。
 *
 * <p>实现应用层端口 {@link KnowledgeVectorIndex}，封装向向量数据库（如 Qdrant）批量写入已持久化的
 * {@code RETRIEVAL} 片段，并支持按 {@code revision_id} 等值过滤删除非活动版本的向量。
 *
 * <p>映射与适配规则：
 * <ul>
 *   <li>主键对齐：Spring AI 2.0.1 要求 Document ID 必须符合标准 UUID 格式（{@link java.util.UUID#fromString}）。
 *       适配器将 MySQL 中存储的无连字符 32 位十六进制业务 ID 规范化为带连字符的标准 UUID 形式，
 *       不生成新的第二套主键。</li>
 *   <li>元数据追踪：在 Document metadata 中保留原始 32 位业务 ID（{@code knowledge_chunk_id}），
 *       同时写入所属知识库、文档、版本、源文件、序号、MIME、源文件 SHA-256、角色（RETRIEVAL）及可选的父片段 ID。</li>
 *   <li>异常处理：捕获底层 VectorStore / EmbeddingModel 抛出的运行时异常，封装为固定安全的业务错误信息，
 *       保留 cause 但避免供应商敏感信息向外泄露。</li>
 * </ul>
 */
public final class SpringAiKnowledgeVectorIndex implements KnowledgeVectorIndex {
    private static final String VECTOR_WRITE_FAILURE = "知识片段向量写入失败";
    private static final String VECTOR_DELETE_FAILURE = "知识片段向量清理失败";

    private final VectorStore vectorStore;

    /**
     * 构造 Spring AI 向量索引适配器。
     *
     * @param vectorStore 底层 Spring AI 向量存储组件实例
     */
    public SpringAiKnowledgeVectorIndex(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 将一批已持久化的检索片段转换为 Spring AI Document 列表并批量写入向量库。
     *
     * @param entries 待写入的向量条目列表（非空，且全部为 RETRIEVAL 角色）
     * @throws IllegalArgumentException 当条目列表为空或 null 时抛出
     * @throws IllegalStateException 当底层向量写入失败时抛出安全异常
     */
    @Override
    public void add(List<KnowledgeVectorEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("向量批次不能为空");
        }
        List<Document> documents = new ArrayList<>(entries.size());
        for (KnowledgeVectorEntry entry : entries) {
            documents.add(toDocument(entry));
        }
        try {
            vectorStore.add(documents);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(VECTOR_WRITE_FAILURE, exception);
        }
    }

    /**
     * 根据版本唯一标识等值过滤删除该版本的所有向量。
     *
     * @param revisionId 目标构建版本 ID
     * @throws IllegalArgumentException 当版本 ID 为空或空白时抛出
     * @throws IllegalStateException 当底层向量清理失败时抛出安全异常
     */
    @Override
    public void deleteByRevisionId(String revisionId) {
        if (revisionId == null || revisionId.isBlank()) {
            throw new IllegalArgumentException("版本 ID 不能为空");
        }
        try {
            FilterExpressionBuilder filters = new FilterExpressionBuilder();
            vectorStore.delete(filters.eq("revision_id", revisionId).build());
        } catch (RuntimeException exception) {
            throw new IllegalStateException(VECTOR_DELETE_FAILURE, exception);
        }
    }

    /**
     * 将应用层向量条目转换为 Spring AI 原生 Document。
     *
     * @param entry 向量条目
     * @return 转换后的 Document 实例
     */
    private static Document toDocument(KnowledgeVectorEntry entry) {
        var chunk = entry.chunk();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("knowledge_base_id", entry.knowledgeBaseId());
        metadata.put("knowledge_document_id", entry.documentId());
        metadata.put("revision_id", chunk.revisionId());
        metadata.put("file_id", entry.fileId());
        metadata.put("knowledge_chunk_id", chunk.id());
        metadata.put("sequence_no", chunk.sequenceNo());
        metadata.put("origin_name", entry.originName());
        metadata.put("mime", entry.mime());
        metadata.put("source_sha256", entry.sourceSha256());
        metadata.put("chunk_role", chunk.chunkRole().value());
        if (chunk.parentChunkId() != null) {
            metadata.put("parent_chunk_id", chunk.parentChunkId());
        }
        String documentId = chunk.id().replaceFirst(
                "^(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})$",
                "$1-$2-$3-$4-$5");
        return new Document(documentId, chunk.content(), metadata);
    }
}
