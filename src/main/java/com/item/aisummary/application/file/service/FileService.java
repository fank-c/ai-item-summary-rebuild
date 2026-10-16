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

/**
 * 文件用例的应用层编排。
 *
 * <p>对象存储和数据库之间没有共同事务，因此本服务明确控制操作顺序，并保留 单边成功可能产生孤立对象或过期元数据的边界。
 */
public class FileService implements FileContentService {
    private final FileRepository repository;
    private final ObjectStorageRouter storage;
    private final FilePolicy policy;

    public FileService(FileRepository repository, ObjectStorageRouter storage, FilePolicy policy) {
        this.repository = repository;
        this.storage = storage;
        this.policy = policy;
    }

    /**
     * 将调用方提供的文件内容上传到指定存储，并登记已完成的文件元数据。
     *
     * <p>先校验显式身份、声明大小、文件名、扩展名、MIME 和存储类型，将厂商目录叠加在公共目录前，生成并检查完整对象 Key。
     * 第一次完整读流核对实际字节数并计算 SHA-256；第二次重开内容，使用实际大小上传。
     * 两次流均由本方法关闭，来源必须保证重开后内容不变，本服务不缓存全文或二次核对摘要。
     *
     * <p>对象上传成功后才插入 status=1、uploadStatus=completed 的记录，所有者和审计用户 均取未经裁剪的
     * actorUserId。创建与更新时间共用一个微秒精度时间，不查询用户表。 对象存储与数据库没有共同事务；插入失败继续传播，可能留下孤立对象，不自动补偿。
     *
     * @param actorUserId 显式调用者 ID，保留原样，最多 64 个 Unicode 码点
     * @param storageType 已启用的目标存储类型，不自动回退到其他厂商
     * @param source 可重开相同内容的上传来源，不能为 null 或提供空内容
     * @return 仓储返回的文件记录，大小与摘要来自第一次实际读取
     * @throws AuthenticationRequiredException 身份缺失或全为空白
     * @throws IllegalArgumentException 身份超过长度上限
     * @throws FileValidationException 上传元数据、Key、实际大小或首次读取不合法
     * @throws ObjectStorageException 第二次开流、上传或关闭上传流失败
     */
    public FileAsset upload(String actorUserId, String storageType, UploadSource source) {
        // 身份必须在读取来源和访问外部存储前通过；内部调用者传入的 ID 保留原样。
        requireActor(actorUserId);
        if (source == null || source.declaredSize() <= 0) {
            throw new FileValidationException("文件不能为空");
        }
        if (source.declaredSize() > policy.maxBytes()) {
            throw new FileValidationException("文件超过大小限制");
        }

        String name = FileRules.requireName(source.originalName(), policy.allowedExtensions());
        String mime = FileRules.normalizeMime(source.mime());
        ObjectStorageRouter.StorageTarget target = storage.resolve(storageType);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // 厂商项目目录只用于新上传；完整 Key 入库，后续操作不再拼接当前配置的目录。
        String prefix =
                target.keyPrefix().isEmpty()
                        ? policy.keyPrefix()
                        : target.keyPrefix() + "/" + policy.keyPrefix();
        // 包含项目目录的完整 Key 在首次读流前检查字符和字节预算。
        String key = FileRules.objectKey(prefix, name, now);
        Digest digest = digest(source);

        // 来源合同保证可以重开相同内容；上传使用第一遍读取到的实际大小和摘要。
        try (InputStream stream = source.openStream()) {
            target.client()
                    .putObject(target.bucket(), key, mime, digest.size(), digest.sha256(), stream);
        } catch (IOException exception) {
            throw new ObjectStorageException("读取上传文件失败", exception);
        }

        // 数据库与对象存储无共同事务；插入失败时可能留下孤立对象，不做自动补偿。
        FileAsset file =
                new FileAsset(
                        UUID.randomUUID().toString().replace("-", ""),
                        name,
                        mime,
                        digest.size(),
                        storageType,
                        key,
                        digest.sha256(),
                        1,
                        null,
                        "completed",
                        actorUserId,
                        actorUserId,
                        now,
                        now);
        return repository.insert(file);
    }

    /**
     * 为调用者拥有且可处理的文件签发临时 GET 下载地址。
     *
     * <p>先检查身份、未删除状态、status=1、completed 和所有者，再按记录中的存储类型 选择适配器。直接使用历史 Key，不应用新上传的 Key
     * 预算，也不探测远端对象是否存在。
     *
     * <p>有效期取文件策略，expiresAt 是本次调用按当前时间估算的到期时间。 返回地址属于临时访问凭证；签名成功不代表已经验证远端下载成功。
     *
     * @param actorUserId 显式调用者 ID，按身份规则校验且不裁剪
     * @param fileId 文件 ID，查询时裁剪首尾空白
     * @return 文件 ID、临时下载地址及估算到期时间
     * @throws AuthenticationRequiredException 身份缺失或全为空白
     * @throws IllegalArgumentException 身份超过长度上限
     * @throws FileNotFoundException 文件不可访问、Key 缺失或已存储类型不可用
     * @throws ObjectStorageException 远端签名操作失败
     */
    public FileDownloadUrl createDownloadUrl(String actorUserId, String fileId) {
        FileAsset file = requireOwned(actorUserId, fileId);
        ObjectStorageRouter.StorageTarget target = targetOf(file);
        Instant expiresAt = Instant.now().plus(policy.downloadExpiration());
        String url =
                target.client()
                        .presignGetObject(
                                target.bucket(), file.storage(), policy.downloadExpiration());
        return new FileDownloadUrl(file.id(), url, expiresAt);
    }

    /**
     * 删除调用者拥有的远端对象，随后逻辑删除对应元数据。
     *
     * <p>先完成与下载相同的身份、归属和可处理状态校验，再删除远端对象。 远端成功后才请求仓储写入逻辑删除标记，并传入审计用户和微秒精度更新时间；
     * 删除时间由数据库生成，文件状态、知识库记录和向量均不在本用例的修改范围。
     *
     * <p>仓储必须恰好更新一行。若对象删除成功但数据库失败，异常继续传播， 记录可能仍然可查询，不恢复对象，也不声称两个系统原子完成。
     *
     * @param actorUserId 显式调用者 ID，同时用于更新审计用户
     * @param fileId 待删除文件 ID，查询时裁剪首尾空白
     * @throws AuthenticationRequiredException 身份缺失或全为空白
     * @throws IllegalArgumentException 身份超过长度上限
     * @throws FileNotFoundException 文件不可访问、Key 缺失或已存储类型不可用
     * @throws ObjectStorageException 远端删除失败，此时不执行数据库逻辑删除
     * @throws IllegalStateException 仓储逻辑删除的影响行数不是一行
     */
    public void delete(String actorUserId, String fileId) {
        FileAsset file = requireOwned(actorUserId, fileId);
        ObjectStorageRouter.StorageTarget target = targetOf(file);
        target.client().deleteObject(target.bucket(), file.storage());

        // 远端已删除时，数据库失败也必须报告失败，不声称两边原子完成。
        int affectedRows =
                repository.softDelete(
                        file.id(), actorUserId, Instant.now().truncatedTo(ChronoUnit.MICROS));
        if (affectedRows != 1) {
            throw new IllegalStateException("逻辑删除文件结果不符合预期");
        }
    }

    /**
     * 为下游登记或处理前校验提供调用者拥有的文件元数据。
     *
     * <p>重新校验显式身份、所有者和可处理状态，只返回名称及 MIME。 不路由对象存储、不打开正文，也不保证本次校验与后续读取构成原子快照。
     * 源文件归属成立不代表调用者获得知识库或其他下游资源的访问权限。
     *
     * @param actorUserId 显式调用者 ID，后台任务同样必须提供
     * @param fileId 源文件 ID，查询时裁剪首尾空白
     * @return 可处理文件的展示名称和 MIME
     * @throws AuthenticationRequiredException 身份缺失或全为空白
     * @throws IllegalArgumentException 身份超过长度上限
     * @throws FileNotFoundException 文件不存在、已删除、状态不符或属于其他用户
     */
    @Override
    public ProcessableFileAsset requireOwnedProcessable(String actorUserId, String fileId) {
        FileAsset file = requireOwned(actorUserId, fileId);
        return new ProcessableFileAsset(file.originName(), file.mime());
    }

    /**
     * 打开调用者拥有且可处理的文件原始内容，供内部处理用例消费。
     *
     * <p>本次调用独立完成身份、归属和状态校验，随后按记录路由存储并打开历史 Key。 包装成功后，流的关闭责任转交给调用者，必须通过 try-with-resources 关闭返回资源。
     * 本方法不解析正文，也不根据元数据重新验证远端内容的大小或 SHA-256。
     *
     * <p>如果资源包装失败，本方法关闭已打开的流，并将关闭失败附加到原异常后继续抛出。 返回元数据是查询时快照，不保证与并发删除或远端内容变更隔离。
     *
     * @param actorUserId 显式调用者 ID，不读取 HTTP 请求上下文
     * @param fileId 源文件 ID，查询时裁剪首尾空白
     * @return 包含文件元数据和原始流的资源，调用者负责关闭
     * @throws AuthenticationRequiredException 身份缺失或全为空白
     * @throws IllegalArgumentException 身份超过长度上限
     * @throws FileNotFoundException 文件不可访问、Key 缺失或已存储类型不可用
     * @throws ObjectStorageException 远端内容打开失败
     */
    @Override
    public FileContentResource openForProcessing(String actorUserId, String fileId) {
        FileAsset file = requireOwned(actorUserId, fileId);
        ObjectStorageRouter.StorageTarget target = targetOf(file);
        InputStream stream = target.client().openObject(target.bucket(), file.storage());
        try {
            return new FileContentResource(
                    file.id(), file.originName(), file.mime(), file.size(), file.sha256(), stream);
        } catch (RuntimeException exception) {
            // 只有资源包装成功才把流所有权交给调用者；包装失败由本方法负责关闭。
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException failure) {
                    exception.addSuppressed(failure);
                }
            }
            throw exception;
        }
    }

    /**
     * 将身份校验和文件可访问性判断集中到所有读取、下载及删除入口共用的边界。
     *
     * <p>身份先于仓储访问校验；文件 ID 缺失或空白直接视为未找到，查询使用裁剪后的 ID。 只有未删除、status=1、completed 且所有者与原始 actorUserId
     * 匹配的记录可以返回。 其他情况统一抛 FileNotFoundException，不向调用者区分他人资源与不存在的资源。
     *
     * @return 查询时的可访问文件快照，不检查远端对象或知识库授权
     */
    private FileAsset requireOwned(String actorUserId, String fileId) {
        requireActor(actorUserId);
        if (fileId == null || fileId.isBlank()) {
            throw new FileNotFoundException();
        }

        // 不存在、状态不符、归属不符共用未找到错误，避免泄露其他用户的文件信息。
        return repository
                .findById(fileId.trim())
                .filter(file -> file.isOwnedProcessable(actorUserId))
                .orElseThrow(FileNotFoundException::new);
    }

    /**
     * 根据已存文件记录选择目标存储，保留历史 Key 的读取兼容性。
     *
     * <p>空 Key 视为资源不可用；路由因类型无效或未启用而拒绝时，转换为文件未找到异常， 避免泄露服务端配置。本方法不重建 Key、不应用新上传长度限制，也不执行 SDK 操作。
     *
     * @return 对应已存储类型的客户端和配置 Bucket
     * @throws FileNotFoundException Key 缺失或已存储类型无法路由
     */
    private ObjectStorageRouter.StorageTarget targetOf(FileAsset file) {
        // 历史 Key 按记录使用，不重新套用仅针对新上传的生成与长度检查。
        if (file.storage() == null || file.storage().isBlank()) {
            throw new FileNotFoundException();
        }
        try {
            return storage.resolve(file.storageType());
        } catch (FileValidationException exception) {
            // 已存储类型不可用时按资源不存在处理，避免暴露服务端存储配置。
            throw new FileNotFoundException();
        }
    }

    /**
     * 校验所有文件用例所需的显式身份，不改变用户 ID 内容。
     *
     * <p>缺失或全为空白时抛身份异常；随后按 Unicode 码点检查 64 字符上限。 不查询用户是否存在，不裁剪首尾空白，不将补充平面字符误计为两个字符。
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

    /**
     * 遍历上传源的实际字节，返回大小与 SHA-256，为第二次上传提供可信元数据。
     *
     * <p>使用固定缓冲区增量读取，不缓存完整文件；以剩余字节预算检查超限， 避免累加溢出。来源声明长度不参与计数，实际为空或超限均拒绝上传。 本方法负责关闭首次读取流，打开、读取或关闭产生的
     * IOException 映射为文件校验错误。
     *
     * @param source 可重开且内容保持不变的上传源
     * @return 实际字节数和小写十六进制 SHA-256
     * @throws FileValidationException 实际内容为空、超限或首次读流失败
     * @throws IllegalStateException 当前运行环境无法提供 SHA-256 算法
     */
    private Digest digest(UploadSource source) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }

        long size = 0;
        byte[] buffer = new byte[8192];
        try (InputStream stream = source.openStream()) {
            int count;
            while ((count = stream.read(buffer)) != -1) {
                // 用剩余预算比较，既限制实际字节，也避免先累加导致 long 溢出。
                if (count > policy.maxBytes() - size) {
                    throw new FileValidationException("文件超过大小限制");
                }
                size += count;
                sha.update(buffer, 0, count);
            }
        } catch (IOException exception) {
            throw new FileValidationException("读取文件失败");
        }

        if (size == 0) {
            throw new FileValidationException("文件不能为空");
        }
        return new Digest(size, HexFormat.of().formatHex(sha.digest()));
    }

    private record Digest(long size, String sha256) {}
}
