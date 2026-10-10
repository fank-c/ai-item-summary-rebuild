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
 * 登记知识库与文件之间的关系。
 *
 * <p>文件归属校验只确认当前调用者可以使用源文件，不代表调用者获得了知识库访问授权。
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
     * 将调用者拥有的可处理文件登记到已存在知识库，只建立文档元数据关系。
     *
     * <p>按显式身份、知识库存在、文件 ID 和源文件归属的顺序校验，再检查库内重复登记。 通过 FileContentService 只校验文件元数据，不打开正文、不调用文件
     * Mapper。 文件归属成立不等于知识库授权，本阶段也不创建权限关系。
     *
     * <p>同一库内重复文件报 DOCUMENT_ALREADY_REGISTERED，数据库唯一索引兜底并发竞争。 新文档使用 32 位
     * UUID、status=1、pending，活动版本和失败摘要为空； 创建与更新时间共用微秒时间，不创建版本、不触发解析或索引任务。
     *
     * @param actorUserId 显式调用者 ID，校验后保留原样，最多 64 个 Unicode 码点
     * @param knowledgeBaseId 已存在知识库 ID，裁剪后最多 32 个 Java 字符
     * @param fileId 源文件 ID，裁剪后最多 32 个 Java 字符
     * @return 仓储保存的 pending 文档记录
     * @throws AuthenticationRequiredException 身份缺失或全为空白
     * @throws IllegalArgumentException 身份超长，或知识库 ID、文件 ID 不合法
     * @throws KnowledgeNotFoundException 知识库不存在
     * @throws com.item.aisummary.domain.file.exception.FileNotFoundException 文件归属或状态校验失败
     * @throws KnowledgeConflictException 同一知识库已经登记该文件
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
     * 查询文档登记快照，不读取源文件或展开版本数据。
     *
     * <p>校验并裁剪文档 ID 后查询仓储；缺失记录使用知识文档未找到异常。 本入口不检查源文件当前状态，也不因 pending、failed 等索引状态隐藏文档。
     *
     * @param id 文档 ID，裁剪后非空白且最多 32 个 Java 字符
     * @return 文档元数据快照
     * @throws IllegalArgumentException ID 不符合文本规则
     * @throws KnowledgeNotFoundException 文档不存在
     */
    public KnowledgeDocument findById(String id) {
        return documents
                .findById(KnowledgeRules.requireId(id, "文档 ID"))
                .orElseThrow(() -> new KnowledgeNotFoundException("知识文档"));
    }

    /**
     * 查询指定知识库已登记的文档，区分父记录不存在与没有子记录。
     *
     * <p>先校验 ID 并确认知识库存在，再按仓储顺序返回登记记录。 不打开源文件、不过滤索引状态，也不将本次存在性检查当作访问授权。
     *
     * @param knowledgeBaseId 知识库 ID，查询前裁剪首尾空白
     * @return 该库的文档列表；有效知识库尚未登记文档时为空列表
     * @throws IllegalArgumentException ID 不符合文本规则
     * @throws KnowledgeNotFoundException 知识库不存在
     */
    public List<KnowledgeDocument> findByKnowledgeBaseId(String knowledgeBaseId) {
        return documents.findByKnowledgeBaseId(requireBase(knowledgeBaseId));
    }

    /**
     * 规范化知识库 ID 并确认父记录存在，为登记和关联列表查询共用。
     *
     * <p>仅验证 ID 的非空白、32 字符上限与记录存在性，不按状态过滤或执行授权。 返回规范化 ID，供后续查询及新文档关联保持一致。
     *
     * @throws IllegalArgumentException ID 不符合文本规则
     * @throws KnowledgeNotFoundException 知识库不存在
     */
    private String requireBase(String value) {
        String id = KnowledgeRules.requireId(value, "知识库 ID");
        bases.findById(id).orElseThrow(() -> new KnowledgeNotFoundException("知识库"));
        return id;
    }

    /**
     * 在访问知识库或文件之前校验登记用例的显式调用者身份。
     *
     * <p>缺失或全为空白时抛身份异常，再按 Unicode 码点检查 64 字符上限。 不裁剪 ID、不读取 HTTP 上下文、不查询用户表，归属判断由文件模块完成。
     *
     * @throws AuthenticationRequiredException 身份缺失或全为空白
     * @throws IllegalArgumentException 身份超过长度上限
     */
    private static void requireActor(String actorUserId) {
        if (actorUserId == null || actorUserId.isBlank()) {
            throw new AuthenticationRequiredException();
        }
        ActorUserIdRules.requireValid(actorUserId);
    }
}
