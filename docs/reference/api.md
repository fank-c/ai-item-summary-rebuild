# HTTP API

当前公开接口为健康检查、用户 CRUD 和文件上传/下载地址/删除。知识库、检索和问答尚无 HTTP 接口。

## 公共约定

- 默认地址：`http://localhost:8080`，可通过 `SERVER_PORT` 更改端口。
- 用户创建和修改使用 `application/json`；文件上传使用 `multipart/form-data`。成功响应直接返回 JSON 对象或数组，无统一包装；`204` 无响应体。
- 健康检查和用户 CRUD 不要求身份。文件接口使用服务端身份检查归属，客户端不提交所有者 ID。
- 默认模拟身份为 `mock-user`，不解析 Authorization、Cookie 或 Session，也不创建数据库用户。创建用户不会切换身份；关闭 `APP_AUTH_MOCK_ENABLED` 后，文件接口返回 `401`，用户 CRUD 仍可调用。
- 可选请求头 `X-Request-Id` 须满足 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`，否则服务生成 UUID。响应头返回该标识，错误体可取得时也返回 `requestId`。
- 响应中的业务 ID 为 32 位无连字符 UUID；时间使用 UTC ISO 8601 字符串，以 `Z` 结尾。

## 接口总览

| 方法 | 路径 | 成功状态 | 响应 |
| --- | --- | --- | --- |
| GET | `/api/health` | 200 | `{"status":"UP"}` |
| POST | `/api/users` | 201 | 用户对象 |
| GET | `/api/users` | 200 | 用户数组 |
| GET | `/api/users/{id}` | 200 | 用户对象 |
| PUT | `/api/users/{id}` | 200 | 用户对象 |
| DELETE | `/api/users/{id}` | 204 | 无 |
| POST | `/api/files?storageType=minio\|aliyun` | 201 | 文件对象 |
| GET | `/api/files/{fileId}/download-url` | 200 | 下载地址对象 |
| DELETE | `/api/files/{fileId}` | 204 | 无 |

## 健康检查

`GET /api/health` 无需参数。`UP` 仅表示 HTTP 服务可达，不探测数据库、模型或对象存储。

## 用户接口

### 创建与修改

`POST /api/users` 和 `PUT /api/users/{id}` 的请求字段：

| 字段 | JSON 类型 | 规则 |
| --- | --- | --- |
| `name` | string | 必填、非空白、最多 100 字符；通过 HTTP 校验后裁剪首尾空白 |
| `email` | string | 必填、合法邮箱、最多 254 字符；保留大小写，不依赖服务修正首尾空格 |
| `password` | string 或 null | 创建必填且非空白；修改省略或 null 保留原密码，空串或全空白拒绝；新密码最多 255 字符且不超过 72 个 UTF-8 字节，保留首尾空格 |

```json
{"name":"Alice","email":"alice@example.test","password":"demo-password"}
```

名称和邮箱允许重复。修改时必须同时提交名称和邮箱，即使只修改密码；`id`、`status` 和时间由服务管理，没有状态修改接口。

创建、详情和修改返回同一用户对象，列表返回该对象数组：

```json
{
  "id": "7d46fda250b64e29a67c104a1e989625",
  "name": "Alice",
  "email": "alice@example.test",
  "status": 1,
  "createAt": "2026-10-08T00:00:00.123456Z",
  "updateAt": "2026-10-08T00:00:00.123456Z"
}
```

响应不含密码、摘要或删除时间。资料更新保留状态和创建时间，刷新修改时间；未提交新密码时不覆盖数据库当前密码。不提供完整并发冲突保护。

### 查询与删除

- `GET /api/users`：返回未删除用户数组，无记录为 `[]`；没有分页、搜索、排序或状态过滤参数，顺序不保证。
- `GET /api/users/{id}`：返回单个用户，不存在或已删除为 `404`。
- `DELETE /api/users/{id}`：逻辑删除，成功为 `204`；不清理关联资源，顺序重复删除为 `404`。

路径 ID 裁剪首尾空白；全空白为 `400`，无 UUID 格式校验。修改不存在或已删除的用户同样返回 `404`。

## 文件接口

### 上传

`POST /api/files?storageType=minio` 使用 multipart 字段 `file`。`storageType` 必填，只接受已启用的 `minio` 或 `aliyun`，不自动回退；需事先创建 Bucket 并配置对应存储。

```bash
curl -i 'http://localhost:8080/api/files?storageType=minio' \
  -F 'file=@document.txt;type=text/plain'
```

- 文件非空，原名非空白、裁剪后最多 255 字符；默认允许 txt、md、pdf、docx，扩展名忽略大小写。上传不会触发文档解析或验证实际格式。
- 单文件和整个 multipart 请求默认均为 20MB，请求预算包含边界及元数据；单个 part 头部默认 4KB。配置入口分别为 `APP_FILE_MAX_SIZE`、`APP_FILE_REQUEST_MAX_SIZE`、`APP_FILE_MAX_PART_HEADER_SIZE`。
- MIME 去参数、裁剪、转小写，最多 255 字符；缺失时为 `application/octet-stream`。
- 所有者、Bucket 和 Key 由服务端确定。文件名完整保存在 `originName`，不作为对象 Key；重复上传不自动去重。

成功返回 `201` 和 `Location: /api/files/{id}`，响应固定为：

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

`size` 为实际字节数；不公开所有者、摘要或存储位置。保存返回的 ID 和原名用于后续操作。`Location` 仅标识资源，目前没有文件列表或详情 GET；GET `/api/files/{fileId}` 返回 `405`。

### 下载地址与删除

文件路径 ID 裁剪首尾空白，空白 ID 按 `404` 处理。操作要求记录未删除、`status=1`、上传完成且归属当前身份；记录不存在、状态不符、非所有者、缺失 Key 或存储适配器不可用统一为 `404`，缺少身份为 `401`。

`GET /api/files/{fileId}/download-url` 返回：

```json
{
  "fileId": "7d46fda250b64e29a67c104a1e989625",
  "url": "https://storage.example.test/temporary-signed-url",
  "expiresAt": "2026-10-08T00:15:00Z"
}
```

客户端直接访问该 URL 下载。默认有效期 15 分钟，可通过 `FILE_STORAGE_DOWNLOAD_URL_EXPIRATION` 配置为 1 秒至 7 天的整数秒。地址是临时凭证，持有者可使用；签发不检查远端对象是否存在。新上传文件的 URL 末段使用 UUID 与扩展名，客户端应使用上传响应的 `originName` 显示或保存原名，签名不额外设置 Content-Disposition。

`DELETE /api/files/{fileId}` 成功为 `204`。先删除对象，再逻辑删除记录；顺序重复删除为 `404`，不清理知识库或向量。对象存储和数据库没有共同事务：上传后数据库失败可能留下孤立对象，删除对象后数据库失败可能留下未删除记录；没有自动补偿或幂等保证。

## 错误响应

错误使用 `application/problem+json`：

```json
{
  "type": "urn:problem:request-validation-error",
  "title": "请求参数错误",
  "status": 400,
  "detail": "请求参数校验失败",
  "instance": "/api/users",
  "code": "REQUEST_VALIDATION_ERROR",
  "requestId": "api-demo-001",
  "errors": [{"field":"password","message":"密码不能为空"}]
}
```

固定字段为 `type/title/status/detail/instance/code`；`type` 为 `urn:problem:` 加错误码的小写连字符形式，`instance` 为请求路径（不含查询字符串）。`requestId` 可取得时返回，`errors` 仅在字段校验失败时提供，每项只含 `field/message`；服务参数错误不保证有字段级信息。调用方按 HTTP 状态与 `code` 判断错误，不依赖说明文本或字段错误顺序。

| HTTP | code | 触发条件 |
| --- | --- | --- |
| 400 | `REQUEST_VALIDATION_ERROR` | 用户字段、密码字符/字节限制或服务参数非法，例如用户路径 ID 为空白 |
| 400 | `REQUEST_BODY_INVALID` | 缺少 JSON 请求体或 JSON 无法解析 |
| 400 | `FILE_VALIDATION_ERROR` | 文件为空、名称/MIME/扩展名非法、业务大小超限、服务端 Key 超预算、存储类型缺失/非法/未启用或 multipart 格式错误 |
| 401 | `AUTHENTICATION_REQUIRED` | 文件接口缺少当前身份 |
| 404 | `RESOURCE_NOT_FOUND` | 资源不存在或不可访问 |
| 405 | `METHOD_NOT_ALLOWED` | 路径不支持所用方法；保留 `Allow` 等协议头 |
| 413 | `FILE_SIZE_LIMIT_EXCEEDED` | 容器拦截单文件、整个请求或 part 头部超限 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 请求 Content-Type 不受支持，保留支持类型协议头 |
| 502 | `OBJECT_STORAGE_UNAVAILABLE` | 对象存储操作失败 |
| 500 | `INTERNAL_ERROR` | 数据库或其他未处理异常，detail 固定为“服务器内部错误” |
