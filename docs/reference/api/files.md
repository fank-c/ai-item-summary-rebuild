# 文件接口

文件模块提供服务端上传、临时下载地址和删除。使用前手动创建 `file_asset` 表和私有 Bucket，并启用目标存储。准备步骤见 [运行指南](../../guides/getting-started.md#4-启用文件存储)，变量见 [配置说明](../configuration.md#上传与共享存储参数)。

返回 [接口总览](./README.md)。身份、请求标识与错误结构见 [公共约定](./common.md)。内部内容读取没有公开 HTTP 路径，调用方式见 [文件模块](../../modules/file.md#内部原始内容读取)。

## 服务端上传

**POST `/api/files?storageType=minio`**

| 参数 | 位置 | 必填 | 规则 |
| --- | --- | --- | --- |
| `storageType` | query | 是 | 仅接受已启用的 `minio` 或 `aliyun`，不自动切换 |
| `file` | multipart | 是 | 非空文件，原文件名非空白、裁剪后最多 255 字符 |

默认允许 txt、md、pdf、docx，忽略扩展名大小写。单文件上限通过 `APP_FILE_MAX_SIZE` 控制，默认 20MB；实际读流时再检查字节数并计算 SHA-256。整个请求通过 `APP_FILE_REQUEST_MAX_SIZE` 控制，默认 20MB，含 multipart 边界和字段开销，因此恰好 20MB 的文件可能超过请求上限。

新上传在首次读流前校验生成 Key：保留 512 的 `String.length()` 上限，另要求 UTF-8 不超过 1023 字节。长多字节前缀和名称共同占用预算；超限为 `400 / FILE_VALIDATION_ERROR`，不截断名称、不读流、不上传或入库。1023 字节为两个存储共用的应用预算，既有记录的下载、删除和内部读取不重新套用此新 Key 检查。

MIME 去参数、裁剪、转小写；缺失时使用 `application/octet-stream`，规范化后最多 255 字符。扩展名与 MIME 不代表内容格式验证，也不代表已实现 PDF/DOCX 解析。

```bash
curl -i -X POST 'http://localhost:8080/api/files?storageType=minio' \
  -F 'file=@document.txt;type=text/plain'
```

PowerShell 使用真正的 curl 可执行文件：

```powershell
curl.exe -i -X POST 'http://localhost:8080/api/files?storageType=minio' -F 'file=@document.txt;type=text/plain'
```

成功为 `201 Created`，带 `Location: /api/files/{id}`。只返回以下七字段，无成功包装：

```json
{
  "id": "7d46fda250b64e29a67c104a1e989625",
  "originName": "document.txt",
  "mime": "text/plain",
  "size": 3,
  "uploadStatus": "completed",
  "createAt": "2026-10-08T00:00:00.123456Z",
  "updateAt": "2026-10-08T00:00:00.123456Z"
}
```

`size` 是实际读取的字节数，时间以 UTC 微秒保存，ID 为 32 位 UUID。所有者来自服务端身份。调用方不能指定所有者、Bucket 或 Key；额外同名查询参数不会改变它们。相同文件名上传生成不同 Key，SHA-256 不用于自动去重。

`Location` 只标识资源。当前没有文件列表、详情 GET、重命名、移动、分享或直传接口；对 `/api/files/{fileId}` 发 GET 返回 405。调用方需要保存上传响应中的 ID，以便后续下载或删除。

缺少身份为 401；文件业务参数错误为 400；容器提前拦截超限为 413；不支持的 Content-Type 为 `415 / UNSUPPORTED_MEDIA_TYPE`；对象存储失败为 502；数据库失败为 500。上传 SDK 返回成功后才保存 completed 记录。

## 获取下载地址

**GET `/api/files/{fileId}/download-url`**

路径参数 `fileId` 为文件 ID，服务裁剪首尾空白后查询，无请求体。服务先校验当前身份、未删除、status=1、completed 和 create_by 匹配。

将 `FILE_ID` 替换为上传响应中的真实 `id`：

```bash
FILE_ID='替换为上传响应中的id'
curl -i "http://localhost:8080/api/files/$FILE_ID/download-url"
```

成功为 `200 OK`，只返回三字段：

```json
{
  "fileId": "7d46fda250b64e29a67c104a1e989625",
  "url": "https://storage.example.test/temporary-signed-url",
  "expiresAt": "2026-10-08T00:15:00Z"
}
```

默认有效期 15m，`FILE_STORAGE_DOWNLOAD_URL_EXPIRATION` 可设 1s～7d，必须为整数秒。示例地址是占位说明，真实地址由已启用 SDK 签发。客户端直接向对象存储下载，不经过应用转发。

URL 自身是临时凭证，任何持有者均能在有效期内使用。接口归属检查仅发生在签发时。签名时不预先检查远端对象；URL 签发成功不能证明对象仍存在。若对象存储 endpoint 是内部网络地址，下载客户端也必须能访问该地址。

缺少身份为 `401 AUTHENTICATION_REQUIRED`。记录不存在、已删除、停用、未完成、非所有者、缺失 Key 或存储类型不可用统一为 `404 RESOURCE_NOT_FOUND`，不暴露他人文件状态。签名失败为 `502 OBJECT_STORAGE_UNAVAILABLE`；数据库错误为 `500 INTERNAL_ERROR`。

## 删除文件

**DELETE `/api/files/{fileId}`**

路径参数和访问校验与下载相同，无请求体。

```bash
curl -i -X DELETE "http://localhost:8080/api/files/$FILE_ID"
```

成功为 `204 No Content`，响应体为空。先删除远端对象，成功后写数据库 `deleted_at=CURRENT_TIMESTAMP(6)`、当前用户 `update_by` 和应用时间 `update_at`，保留原 status。远端删除失败返回 502，数据库不标记删除。

完成后，签名、顺序重复删除和内部读取返回 404。文件删除不会清理知识库内容或向量数据。

上传成功而保存失败可能留下没有记录的对象；远端删除成功而数据库失败可能留下未删除记录。当前没有自动补偿或清理。上传超时也可能远端生效，重新上传会使用新 Key；不承诺幂等或完整并发冲突治理。

## 源码与测试入口

- [FileController](../../../src/main/java/com/item/aisummary/web/file/controller/FileController.java)、[FileResponse](../../../src/main/java/com/item/aisummary/web/file/response/FileResponse.java)：接口路径、状态码和上传响应。
- [FileService](../../../src/main/java/com/item/aisummary/application/file/service/FileService.java)：文件用例与归属检查。
- [FileHttpTest](../../../src/test/java/com/item/aisummary/web/file/controller/FileHttpTest.java)：H2 与存储替身上的文件生命周期和响应字段。
- [FileAuthenticationHttpTest](../../../src/test/java/com/item/aisummary/web/file/controller/FileAuthenticationHttpTest.java)、[FileFailureHttpTest](../../../src/test/java/com/item/aisummary/web/file/controller/FileFailureHttpTest.java)：身份与失败边界。
- [FileMultipartHttpTest](../../../src/test/java/com/item/aisummary/web/file/controller/FileMultipartHttpTest.java)：真实容器 multipart 限制。
- [FileKeyValidationHttpTest](../../../src/test/java/com/item/aisummary/web/file/controller/FileKeyValidationHttpTest.java)：超长 Key 的 400 和无写入副作用。

测试命令与验证边界见 [测试说明](../../guides/testing.md)。
