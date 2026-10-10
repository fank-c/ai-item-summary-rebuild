# 文件模块实现说明

文件模块接收服务端 multipart 上传，保存 MySQL 元数据和远端对象，提供临时下载地址、删除以及内部原始内容读取。当前不解析文档，也不调用 AI。

学习目标是理解数据库与对象存储的职责分离、归属校验、可重开上传流以及资源释放。公开输入、七字段上传响应和调用示例见 [文件接口](../reference/api/files.md)，错误码见 [公共约定](../reference/api/common.md#错误码)，存储准备与开关见 [运行指南](../guides/getting-started.md#4-启用文件存储)。返回 [文档导航](../README.md)。

公开接口只有上传、下载地址和删除，没有文件列表或详情 GET。上传响应的 Location 标识资源，不表示存在可查询的详情接口。

## 上传：先校验，再两次读流

```text
校验身份 → 声明大小、名称、MIME、存储类型和 Key
→ 第一次读流：计算实际字节数与 SHA-256，关闭流
→ 第二次打开相同内容：按实际大小上传，关闭流
→ 写入 completed 元数据 → 返回七字段响应
```

先检查声明大小可以尽早拒绝明显无效输入，但不能信任声明长度。第一次遍历完整内容才得到实际大小和摘要；超过上限时立即拒绝，实际为空也失败。

`UploadSource` 必须能重开同一份内容，并保证两次读取之间内容不变。服务不会把全部字节缓存到内存，也不在两次读取时再次核对摘要；内容稳定性属于来源合同。

上传成功后，元数据保存实际大小、SHA-256、存储类型、对象 Key、`status=1`、`uploadStatus=completed` 和审计时间。`create_by` 是所有者；不额外查询用户表。

## 文本与 Key 规则

- 展示名称只裁剪首尾空白，最多 255 字符；提取路径末段后校验扩展名，忽略大小写，默认允许 txt、md、pdf、docx。路径和特殊字符的清理只用于生成对象 Key。
- MIME 去参数、转小写，最多 255 字符；缺少时使用 `application/octet-stream`。
- Key 使用配置前缀、UTC 日期、随机 UUID 和清理后的名称。Bucket 只来自服务端配置。
- 新 Key 同时限制 `String.length()` 不超过 512、UTF-8 不超过 1023 字节，首次读流前检查，不截断文件名。

字节预算用于兼顾两个厂商；多字节前缀和名称共同占用预算。历史 Key 的下载、删除及读取直接使用记录值，不套用新上传的生成校验。

扩展名和 MIME 校验不识别文件真实格式，不做内容嗅探、病毒检测或解析。容器限制整个 multipart 请求，服务另核对实际内容，具体大小与超时配置见 [配置说明](../reference/configuration.md#上传与共享存储参数)。

## 身份、归属与状态

所有文件服务入口先检查身份非空且最多 64 个 Unicode 码点，再访问仓储或读流。内部 ID 保留原样，不裁剪、不转大小写、不截断；超长内部 ID 抛 `IllegalArgumentException`，经 HTTP 传播时映射为 400。

可下载、删除或处理的文件必须同时满足：

```text
deleted_at IS NULL && status == 1 && uploadStatus == completed && create_by == actorUserId
```

文件不存在、状态不符或不属于调用者，统一返回 404，避免暴露他人的资源。缺少身份返回 401。HTTP 身份来自服务端请求属性，客户端不能提交所有者参数。

模拟身份默认 `mock-user`，不会创建数据库用户。真实认证和角色权限尚未实现，详见 [工程结构](../architecture.md#请求身份与用户引用)。

## 下载地址与删除

下载地址基于记录中的存储类型和 Key 生成 GET 签名，不发起 HEAD 或探测远端对象。默认有效期 15 分钟，可配置为 1～604800 整数秒。

地址是临时访问凭证，持有地址的人可以下载。元数据存在不保证远端对象存在；签名成功也不等于已经验证下载成功。

删除先删除远端对象，再逻辑删除数据库一行，写入数据库生成的 `deleted_at` 并刷新 `update_by/update_at`；保留 `status`。删除不清理知识库内容或向量。

## 内部原始内容读取

`application.file.content.FileContentService` 没有公开 HTTP 路径；下游只需引用该包中的合同和资源类型，调用时显式传用户 ID：

```java
var metadata = fileContentService.requireOwnedProcessable(actorUserId, fileId);

try (var content = fileContentService.openForProcessing(actorUserId, fileId)) {
    // 消费 content.inputStream()；处理完成或失败都会关闭返回流。
}
```

`requireOwnedProcessable` 返回名称和 MIME，供处理前校验；`openForProcessing` 再校验归属并返回元数据与流。两次调用不提供原子快照。

调用者通过 try-with-resources 关闭 `FileContentResource`。OSS 返回的流关闭时同时释放 SDK 的 `OSSObject`；后台任务必须自行传入身份，不能使用 HTTP 请求上下文。

## 存储与失败边界

MinIO 和 OSS 适配器直接实现上传、打开流、GET 签名和删除。仅启用的类型可用，不回退到其他厂商；配置启用时缺必填项拒绝启动，但不会自动建 Bucket 或校验真实凭据。MinIO 的 HTTP 客户端关闭连接失败重试，OSS 的 `maxErrorRetry` 设为 0；应用层不额外包装重试。

| 失败位置 | 行为与影响 |
| --- | --- |
| 业务输入或首次读流失败 | 400 `FILE_VALIDATION_ERROR`，未开始对象上传 |
| 容器 multipart 超限 | 413 `FILE_SIZE_LIMIT_EXCEEDED` |
| 第二次开流或 SDK 操作失败 | 502 `OBJECT_STORAGE_UNAVAILABLE`，不回显厂商细节 |
| 对象上传成功、数据库插入失败 | 500，可能留下无元数据的孤立对象 |
| 对象删除成功、数据库更新失败 | 500，记录可能仍可查询，远端对象已不存在 |

数据库和对象存储没有共同事务，也没有自动补偿、重试、幂等性或完整并发治理。连接与读写超时不保证整个流处理的总时长。

## 代码地图

| 位置 | 职责 | 关键源码 |
| --- | --- | --- |
| `web.file.controller`、`response`、`upload` | 三个 HTTP 接口、七字段响应与 multipart 转换 | [FileController](../../src/main/java/com/item/aisummary/web/file/controller/FileController.java)、[FileResponse](../../src/main/java/com/item/aisummary/web/file/response/FileResponse.java)、[MultipartUploadSource](../../src/main/java/com/item/aisummary/web/file/upload/MultipartUploadSource.java) |
| `application.file.service`、`policy`、`result` | 上传、签名、删除和内部读取用例、约束及下载结果 | [FileService](../../src/main/java/com/item/aisummary/application/file/service/FileService.java)、[FilePolicy](../../src/main/java/com/item/aisummary/application/file/policy/FilePolicy.java)、[FileDownloadUrl](../../src/main/java/com/item/aisummary/application/file/result/FileDownloadUrl.java) |
| `application.file.content` | 下游显式身份内容读取合同与资源所有权 | [FileContentService](../../src/main/java/com/item/aisummary/application/file/content/FileContentService.java)、[ProcessableFileAsset](../../src/main/java/com/item/aisummary/application/file/content/ProcessableFileAsset.java)、[FileContentResource](../../src/main/java/com/item/aisummary/application/file/content/FileContentResource.java) |
| `application.file.port` | 仓储、可重开内容流、存储能力和路由合同 | [UploadSource](../../src/main/java/com/item/aisummary/application/file/port/UploadSource.java)、[ObjectStorageClient](../../src/main/java/com/item/aisummary/application/file/port/ObjectStorageClient.java)、[FileRepository](../../src/main/java/com/item/aisummary/application/file/port/FileRepository.java) |
| `domain.file.model`、`rule`、`exception` | 文件数据、名称、MIME、Key 与可处理状态规则、业务异常 | [FileRules](../../src/main/java/com/item/aisummary/domain/file/rule/FileRules.java)、[FileAsset](../../src/main/java/com/item/aisummary/domain/file/model/FileAsset.java) |
| `infrastructure.file.config`、`persistence.entity / mapper / repository`、`storage` | 配置装配、MyBatis 和原始 MinIO / OSS SDK 适配 | [FileConfiguration](../../src/main/java/com/item/aisummary/infrastructure/file/config/FileConfiguration.java)、[MyBatisFileRepository](../../src/main/java/com/item/aisummary/infrastructure/file/persistence/repository/MyBatisFileRepository.java)、[存储适配器目录](../../src/main/java/com/item/aisummary/infrastructure/file/storage/) |

## 测试与学习入口

| 测试 | 可观察行为 |
| --- | --- |
| [FileServiceTest](../../src/test/java/com/item/aisummary/application/file/service/FileServiceTest.java) | 实际大小、摘要、两次关闭、身份先行、Key 校验及跨存储失败 |
| [FileRulesTest](../../src/test/java/com/item/aisummary/domain/file/rule/FileRulesTest.java) | UTF-8 字节边界、UTC 日期与名称清理 |
| [FileHttpTest](../../src/test/java/com/item/aisummary/web/file/controller/FileHttpTest.java) | H2 真 Mapper 生命周期、七字段响应、归属状态及内部内容读取 |
| [FileAuthenticationHttpTest](../../src/test/java/com/item/aisummary/web/file/controller/FileAuthenticationHttpTest.java) | 关闭模拟身份后的 401 |
| [FileKeyValidationHttpTest](../../src/test/java/com/item/aisummary/web/file/controller/FileKeyValidationHttpTest.java) | 超长 Key 的 400 与无写入副作用 |
| [FileFailureHttpTest](../../src/test/java/com/item/aisummary/web/file/controller/FileFailureHttpTest.java) | 对象与数据库单边成功后的安全错误 |
| [FileMultipartHttpTest](../../src/test/java/com/item/aisummary/web/file/controller/FileMultipartHttpTest.java) | 真实嵌入式容器的文件与总请求大小限制 |
| [StorageAdapterTest](../../src/test/java/com/item/aisummary/infrastructure/file/storage/StorageAdapterTest.java)、[FileConfigurationTest](../../src/test/java/com/item/aisummary/infrastructure/file/config/FileConfigurationTest.java) | SDK 元数据、签名、流资源、异常与条件装配 |

普通测试使用存储替身或 SDK Mock，不访问真实 Bucket。命令与验证边界见 [测试说明](../guides/testing.md)。
