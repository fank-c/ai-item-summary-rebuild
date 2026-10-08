package com.item.aisummary.application.file;

import com.item.aisummary.application.auth.AuthenticationRequiredException;
import com.item.aisummary.application.file.port.*;
import com.item.aisummary.domain.file.*;
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
        requireActor(actorUserId);
        if (source == null || source.declaredSize() <= 0) throw new FileValidationException("文件不能为空");
        if (source.declaredSize() > policy.maxBytes()) throw new FileValidationException("文件超过大小限制");
        String name = FileRules.requireName(source.originalName(), policy.allowedExtensions());
        String mime = FileRules.normalizeMime(source.mime());
        var target = storage.resolve(storageType);
        Digest digest = digest(source);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String key = FileRules.objectKey(policy.keyPrefix(), name, now);
        try (InputStream stream = source.openStream()) {
            target.client().putObject(target.bucket(), key, mime, digest.size(), digest.sha256(), stream);
        } catch (IOException exception) {
            throw new ObjectStorageException("读取上传文件失败", exception);
        }
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
            if (stream != null) {
                try { stream.close(); } catch (IOException failure) { exception.addSuppressed(failure); }
            }
            throw exception;
        }
    }

    private FileAsset requireOwned(String actorUserId, String fileId) {
        requireActor(actorUserId);
        if (fileId == null || fileId.isBlank()) throw new FileNotFoundException();
        return repository.findById(fileId.trim()).filter(file -> file.isOwnedProcessable(actorUserId))
            .orElseThrow(FileNotFoundException::new);
    }

    private ObjectStorageRouter.StorageTarget targetOf(FileAsset file) {
        if (file.storage() == null || file.storage().isBlank()) throw new FileNotFoundException();
        try { return storage.resolve(file.storageType()); }
        catch (FileValidationException exception) { throw new FileNotFoundException(); }
    }

    private static void requireActor(String actorUserId) {
        if (actorUserId == null || actorUserId.isBlank()) throw new AuthenticationRequiredException();
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
