package com.item.aisummary.application.knowledge.indexing.vector;

import com.item.aisummary.domain.knowledge.enums.ChunkRole;
import com.item.aisummary.domain.knowledge.model.KnowledgeChunk;
import com.item.aisummary.domain.knowledge.rule.KnowledgeRules;

/**
 * 待写入向量索引的片段条目合同。
 *
 * <p>携带已在 MySQL 完成插入并提交的 {@code RETRIEVAL} 角色片段及其来源上下文，准备传递给
 * {@link KnowledgeVectorIndex#add}。
 *
 * <p>业务规则与设计约束：
 * <ul>
 *   <li>正文必须直接从已保存的 {@link KnowledgeChunk} 中读取，严禁重新读取或解析源文件，保证向量正文与数据库片段完全一致。</li>
 *   <li>片段角色必须严格为 {@code RETRIEVAL}；{@code CONTEXT} 上下文父块只持久化到数据库，不参与向量化与向量检索。</li>
 *   <li>片段 ID 必须为已经预分配的 32 位十六进制业务 UUID；底层向量适配器在写入时转换为标准带连字符 UUID。</li>
 *   <li>来源元数据（知识库 ID、文档 ID、文件 ID、文件名、MIME 及实际 SHA-256）必须有效，随 Document 元数据一同写入向量库用于追踪与等值清理。</li>
 * </ul>
 *
 * @param knowledgeBaseId 所属知识库业务 ID
 * @param documentId 所属知识文档业务 ID
 * @param fileId 源文件业务 ID
 * @param originName 源文件原始展示名称
 * @param mime 规范化后的 MIME 类型
 * @param sourceSha256 实际读取并核对的源文件 SHA-256 摘要
 * @param chunk 已完成持久化的检索片段实体（必须为 RETRIEVAL 角色）
 */
public record KnowledgeVectorEntry(
        String knowledgeBaseId, String documentId, String fileId, String originName,
        String mime, String sourceSha256, KnowledgeChunk chunk) {
    public KnowledgeVectorEntry {
        knowledgeBaseId = KnowledgeRules.requireId(knowledgeBaseId, "知识库 ID");
        documentId = KnowledgeRules.requireId(documentId, "文档 ID");
        fileId = KnowledgeRules.requireId(fileId, "文件 ID");
        if (chunk == null || chunk.chunkRole() != ChunkRole.RETRIEVAL
                || chunk.id() == null || !chunk.id().matches("[0-9a-fA-F]{32}")
                || chunk.revisionId() == null || chunk.revisionId().isBlank()) {
            throw new IllegalArgumentException("向量输入必须是已分配业务 UUID 的检索块");
        }
        if (originName == null || originName.isBlank() || mime == null || mime.isBlank()
                || sourceSha256 == null || !sourceSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("向量来源信息无效");
        }
    }

    /**
     * 防御性重写 toString，避免在日志框架输出包含用户隐私的正文文本或文件名。
     */
    @Override public String toString() {
        return "KnowledgeVectorEntry[chunkId=" + chunk.id() + ", revisionId=" + chunk.revisionId() + "]";
    }
}
