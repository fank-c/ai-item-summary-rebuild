package com.item.aisummary.application.file.service;

import com.item.aisummary.application.auth.ActorUserIdRules;
import com.item.aisummary.application.auth.AuthenticationRequiredException;
import com.item.aisummary.application.file.content.FileContentResource;
import com.item.aisummary.application.file.content.FileContentService;
import com.item.aisummary.application.file.content.ProcessableFileAsset;
import com.item.aisummary.application.file.policy.FilePolicy;
import com.item.aisummary.application.file.port.FileRepository;
import com.item.aisummary.application.file.port.ObjectStorageException;
import com.item.aisummary.application.file.port.ObjectStorageRouter;
import com.item.aisummary.application.file.port.UploadSource;
import com.item.aisummary.application.file.result.FileDownloadUrl;
import com.item.aisummary.domain.file.exception.FileNotFoundException;
import com.item.aisummary.domain.file.exception.FileValidationException;
import com.item.aisummary.domain.file.model.FileAsset;
import com.item.aisummary.domain.file.rule.FileRules;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

/** 核心文件用例；外部操作只经 port，无直传、补偿、HEAD 或可用性包装。 */
public class FileService implements FileContentService {
    private final FileRepository repository;
    private final ObjectStorageRouter storage;
    private final FilePolicy policy;

    public FileService(FileRepository repository, ObjectStorageRouter storage, FilePolicy policy) {
        this.repository = repository; this.storage = storage; this.policy = policy;
    }

    /** 摘要读取完整内容后重开流上传；远端成功后才插入 completed 元数据。 */
    public FileAsset upload(String actorUserId, String storageType, UploadSource source) {
        // 身份必须在来源读取和外部访问前通过；内部调用者传入的 ID 保留原样。
        requireActor(actorUserId);
        if (source == null || source.declaredSize() <= 0) throw new FileValidationException("文件不能为空");
        if (source.declaredSize() > policy.maxBytes()) throw new FileValidationException("文件超过大小限制");
        String name = FileRules.requireName(source.originalName(), policy.allowedExtensions());
        String mime = FileRules.normalizeMime(source.mime());
        var target = storage.resolve(storageType);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // 新 Key 的字符与字节预算在首次读流前检查，避免上传后才被存储或数据库拒绝。
        String key = FileRules.objectKey(policy.keyPrefix(), name, now);
        Digest digest = digest(source);
        // 来源合同保证重开内容不变；使用第一遍的实际大小和摘要，而非声明长度。
        try (InputStream stream = source.openStream()) {
            target.client().putObject(target.bucket(), key, mime, digest.size(), digest.sha256(), stream);
        } catch (IOException exception) {
            throw new ObjectStorageException("读取上传文件失败", exception);
        }
        // 数据库与对象存储无共同事务；此处失败可能留下孤立对象，没有自动补偿。
        return repository.insert(new FileAsset(UUID.randomUUID().toString().replace("-", ""), name, mime,
            digest.size(), storageType, key, digest.sha256(), 1, null, "completed", actorUserId, actorUserId, now, now));
    }

    /** 签发下载凭证时校验归属；不预先探测远端对象。 */
    public FileDownloadUrl createDownloadUrl(String actorUserId, String fileId) {
        FileAsset file = requireOwned(actorUserId, fileId);
        var target = targetOf(file);
        Instant expiresAt = Instant.now().plus(policy.downloadExpiration());
        String url = target.client().presignGetObject(target.bucket(), file.storage(), policy.downloadExpiration());
        return new FileDownloadUrl(file.id(), url, expiresAt);
    }

    /** 先删除对象，再写数据库删除标记；不修改 status，不清理知识库。 */
    public void delete(String actorUserId, String fileId) {
        FileAsset file = requireOwned(actorUserId, fileId);
        var target = targetOf(file);
        target.client().deleteObject(target.bucket(), file.storage());
        // 远端已删除时，数据库失败也必须报告失败；不会恢复对象或声称两边原子完成。
        if (repository.softDelete(file.id(), actorUserId, Instant.now().truncatedTo(ChronoUnit.MICROS)) != 1) {
            throw new IllegalStateException("逻辑删除文件结果不符合预期");
        }
    }

    @Override public ProcessableFileAsset requireOwnedProcessable(String actorUserId, String fileId) {
        FileAsset file = requireOwned(actorUserId, fileId);
        return new ProcessableFileAsset(file.originName(), file.mime());
    }

    @Override public FileContentResource openForProcessing(String actorUserId, String fileId) {
        FileAsset file = requireOwned(actorUserId, fileId);
        var target = targetOf(file);
        InputStream stream = target.client().openObject(target.bucket(), file.storage());
        try {
            return new FileContentResource(file.id(), file.originName(), file.mime(), file.size(), file.sha256(), stream);
        } catch (RuntimeException exception) {
            // 只有资源包装成功才把流所有权交给调用者；包装失败由本方法负责关闭。
            if (stream != null) {
                try { stream.close(); } catch (IOException failure) { exception.addSuppressed(failure); }
            }
            throw exception;
        }
    }

    private FileAsset requireOwned(String actorUserId, String fileId) {
        requireActor(actorUserId);
        if (fileId == null || fileId.isBlank()) throw new FileNotFoundException();
        // 不存在、状态不符、归属不符共用未找到错误，避免泄露其他用户的文件信息。
        return repository.findById(fileId.trim()).filter(file -> file.isOwnedProcessable(actorUserId))
            .orElseThrow(FileNotFoundException::new);
    }

    private ObjectStorageRouter.StorageTarget targetOf(FileAsset file) {
        // 历史 Key 按记录使用，不重新套用仅针对新上传的生成与长度检查。
        if (file.storage() == null || file.storage().isBlank()) throw new FileNotFoundException();
        try { return storage.resolve(file.storageType()); }
        catch (FileValidationException exception) { throw new FileNotFoundException(); }
    }

    private static void requireActor(String actorUserId) {
        if (actorUserId == null || actorUserId.isBlank()) throw new AuthenticationRequiredException();
        ActorUserIdRules.requireValid(actorUserId);
    }

    /** 必须按实际读取字节计数，不能相信声明的 multipart 长度。 */
    private Digest digest(UploadSource source) {
        MessageDigest sha;
        try { sha = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        long size = 0;
        byte[] buffer = new byte[8192];
        try (InputStream stream = source.openStream()) {
            int count;
            while ((count = stream.read(buffer)) != -1) {
                // 用剩余预算比较，既限制实际字节，也避免先累加导致 long 溢出。
                if (count > policy.maxBytes() - size) throw new FileValidationException("文件超过大小限制");
                size += count;
                sha.update(buffer, 0, count);
            }
        } catch (IOException exception) { throw new FileValidationException("读取文件失败"); }
        if (size == 0) throw new FileValidationException("文件不能为空");
        return new Digest(size, HexFormat.of().formatHex(sha.digest()));
    }
    private record Digest(long size, String sha256) { }
}
