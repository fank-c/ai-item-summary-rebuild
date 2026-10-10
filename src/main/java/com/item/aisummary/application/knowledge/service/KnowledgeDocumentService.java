package com.item.aisummary.application.knowledge.service;

import com.item.aisummary.application.auth.ActorUserIdRules;
import com.item.aisummary.application.auth.AuthenticationRequiredException;
import com.item.aisummary.application.file.content.FileContentService;
import com.item.aisummary.application.knowledge.port.KnowledgeBaseRepository;
import com.item.aisummary.application.knowledge.port.KnowledgeDocumentRepository;
import com.item.aisummary.domain.knowledge.enums.DocumentIndexStatus;
import com.item.aisummary.domain.knowledge.exception.KnowledgeConflictException;
import com.item.aisummary.domain.knowledge.exception.KnowledgeNotFoundException;
import com.item.aisummary.domain.knowledge.model.KnowledgeDocument;
import com.item.aisummary.domain.knowledge.rule.KnowledgeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 知识文档（KnowledgeDocument）登记与关联服务。
 *
 * <p>负责建立知识库与源文件的关联元数据，维护 pending 初始状态文档。
 */
public final class KnowledgeDocumentService {
    private final KnowledgeBaseRepository bases;
    private final KnowledgeDocumentRepository documents;
    private final FileContentService files;
    private final Clock clock;

    public KnowledgeDocumentService(
            KnowledgeBaseRepository bases,
            KnowledgeDocumentRepository documents,
            FileContentService files,
            Clock clock) {
        this.bases = bases;
        this.documents = documents;
        this.files = files;
        this.clock = clock;
    }

    /**
     * 将调用者拥有的源文件登记到指定知识库（校验文件所有权与查重，初始化 pending 文档）。
     *
     * @param actorUserId 调用者用户 ID
     * @param knowledgeBaseId 目标知识库 ID
     * @param fileId 已上传的源文件 ID
     * @return 登记成功的 pending 状态文档实体
     */
    public KnowledgeDocument register(String actorUserId, String knowledgeBaseId, String fileId) {
        requireActor(actorUserId);
        String baseId = requireBase(knowledgeBaseId);
        String sourceFileId = KnowledgeRules.requireId(fileId, "文件 ID");
        files.requireOwnedProcessable(actorUserId, sourceFileId);

        if (documents.findByKnowledgeBaseAndFile(baseId, sourceFileId).isPresent()) {
            throw new KnowledgeConflictException("DOCUMENT_ALREADY_REGISTERED", "该文件已登记到此知识库");
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        KnowledgeDocument document =
                new KnowledgeDocument(
                        UUID.randomUUID().toString().replace("-", ""),
                        baseId,
                        sourceFileId,
                        1,
                        DocumentIndexStatus.PENDING,
                        null,
                        null,
                        null,
                        now,
                        now);
        return documents.insert(document);
    }

    /**
     * 根据文档唯一标识查询知识文档元数据快照。
     *
     * <p>仅查询单条文档元数据，不读取底层源文件内容，不展开历史版本列表，亦不按索引状态过滤。
     *
     * @param id 文档唯一标识（32 位 UUID）
     * @return 匹配的知识文档实体快照
     * @throws IllegalArgumentException 当文档 ID 为空或格式非法时抛出
     * @throws KnowledgeNotFoundException 当指定文档不存在时抛出
     */
    public KnowledgeDocument findById(String id) {
        return documents
                .findById(KnowledgeRules.requireId(id, "文档 ID"))
                .orElseThrow(() -> new KnowledgeNotFoundException("知识文档"));
    }

    /**
     * 查询指定知识库下登记的所有文档列表。
     *
     * <p>查询前校验知识库存在性；返回列表保留仓储默认顺序。
     *
     * @param knowledgeBaseId 知识库唯一标识（32 位 UUID）
     * @return 该知识库下的文档列表；若知识库存在但尚未登记任何文档则返回空列表
     * @throws IllegalArgumentException 当知识库 ID 为空或格式非法时抛出
     * @throws KnowledgeNotFoundException 当指定的知识库不存在时抛出
     */
    public List<KnowledgeDocument> findByKnowledgeBaseId(String knowledgeBaseId) {
        return documents.findByKnowledgeBaseId(requireBase(knowledgeBaseId));
    }

    /**
     * 校验知识库 ID 格式并确认知识库真实存在。
     *
     * @param value 原始知识库 ID 字符串
     * @return 规范化后的知识库 ID
     * @throws IllegalArgumentException 当知识库 ID 格式非法时抛出
     * @throws KnowledgeNotFoundException 当知识库不存在时抛出
     */
    private String requireBase(String value) {
        String id = KnowledgeRules.requireId(value, "知识库 ID");
        bases.findById(id).orElseThrow(() -> new KnowledgeNotFoundException("知识库"));
        return id;
    }

    /**
     * 校验显式调用者身份 ID 的合法性。
     *
     * <p>要求非空、非空白，且按照 Unicode 码点计算不超过 64 个字符。
     *
     * @param actorUserId 待校验的调用者 ID
     * @throws AuthenticationRequiredException 当身份为 null 或全为空白时抛出
     * @throws IllegalArgumentException 当 Unicode 码点超出 64 时抛出
     */
    private static void requireActor(String actorUserId) {
        if (actorUserId == null || actorUserId.isBlank()) {
            throw new AuthenticationRequiredException();
        }
        ActorUserIdRules.requireValid(actorUserId);
    }
}
