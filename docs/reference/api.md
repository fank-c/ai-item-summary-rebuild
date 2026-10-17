# HTTP API

当前公开接口为健康检查、用户 CRUD、文件操作及知识库管理（知识库元数据、文档登记与同步构建、版本与切片只读查询）。语义检索和问答尚无 HTTP 接口。

## 公共约定

- 默认地址：`http://localhost:8080`，可通过 `SERVER_PORT` 更改端口。
- 请求与响应格式：用户、知识库创建/修改/构建使用 `application/json`；文件上传使用 `multipart/form-data`。成功响应直接返回 JSON 对象或数组，无统一包装；`204` 无响应体。
- 路径前缀：知识库相关接口统一使用 `/api/v1/knowledge-bases`、`/api/v1/knowledge-documents`、`/api/v1/knowledge-revisions`、`/api/v1/knowledge-chunks` 路径前缀。
- 身份与授权边界：健康检查和用户 CRUD 不要求身份；文件接口与所有知识库接口（全部 12 个）均通过 `LoginUserContext` 要求服务端有效身份，客户端不提交所有者 ID。默认模拟身份为 `mock-user`，不解析 Authorization、Cookie 或 Session，也不创建数据库用户。关闭 `APP_AUTH_MOCK_ENABLED` 后，文件与知识库接口返回 `401`（`AUTHENTICATION_REQUIRED`），用户 CRUD 仍可调用。
- 学习工程边界：当前系统为学习工程阶段，尚未实现知识库专用的访问控制列表或用户授权模型。现有源文件所有权仅在“文档登记”与“文档同步构建”时用于校验操作者对底层文件的合法归属；其余知识库、文档、版本和切片片段的元数据与正文查询均对任意已绑定身份开放。明确此为学习阶段工程边界，不声称已具备租户隔离或授权防护。
- 知识库路径 ID（`baseId`、`documentId`、`revisionId`、`chunkId`）经裁剪首尾空白后必须非空且不超过 32 个字符，不执行 UUID 格式强校验；非法返回 `400`。用户与文件路径规则分别见对应章节。
- 列表与顺序：查询列表均无分页。知识库列表与文档列表无强顺序保证；版本列表严格按版本号 `revisionNumber` 升序排列；切片片段列表严格按版本内全局序号 `sequenceNo` 升序排列。
- 响应字段与枚举规范：响应中的业务 ID 为 32 位无连字符字符串；时间均使用 UTC ISO 8601 字符串（微秒精度，以 `Z` 结尾）。枚举字符串响应格式：
  - `indexStatus`：小写枚举值，包含 `pending`（等待构建）、`indexed`（已完成构建与发布，当前尚无检索接口）、`failed`（索引失败）、`removed`（已移除）。
  - `revisionStatus`：小写枚举值，包含 `building`（构建中）、`ready`（构建就绪）、`failed`（构建失败）、`superseded`（已被新版本替代）。
  - `chunkRole`：大写枚举值，包含 `CONTEXT`（上下文块）与 `RETRIEVAL`（检索块）。
- 请求头关联：可选请求头 `X-Request-Id` 须满足 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`，否则服务生成 UUID。响应头返回该标识，错误体可取得时也返回 `requestId`。

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
| POST | `/api/v1/knowledge-bases` | 201 | 知识库对象（带 Location） |
| GET | `/api/v1/knowledge-bases` | 200 | 知识库数组 |
| GET | `/api/v1/knowledge-bases/{baseId}` | 200 | 知识库对象 |
| PUT | `/api/v1/knowledge-bases/{baseId}` | 200 | 知识库对象 |
| POST | `/api/v1/knowledge-bases/{baseId}/documents` | 201 | 知识文档对象（带 Location） |
| GET | `/api/v1/knowledge-bases/{baseId}/documents` | 200 | 知识文档数组 |
| GET | `/api/v1/knowledge-documents/{documentId}` | 200 | 知识文档对象 |
| POST | `/api/v1/knowledge-documents/{documentId}/builds` | 200 | 构建完成事实对象 |
| GET | `/api/v1/knowledge-documents/{documentId}/revisions` | 200 | 版本数组（升序） |
| GET | `/api/v1/knowledge-revisions/{revisionId}` | 200 | 版本对象 |
| GET | `/api/v1/knowledge-revisions/{revisionId}/chunks` | 200 | 片段数组（升序） |
| GET | `/api/v1/knowledge-chunks/{chunkId}` | 200 | 片段对象 |

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

## 知识库接口

知识库提供元数据管理、文档登记、同步构建以及版本与切片只读查询共 12 个 HTTP 接口。基础版本创建与批量片段写入为内部服务能力，不暴露 HTTP；文档版本生命周期由构建接口驱动。

### 知识库元数据

知识库仅维护自身元数据，不包含所有者或权限关系，当前不提供删除接口。

- `POST /api/v1/knowledge-bases`：创建知识库。
  - 请求体：`{"name": "知识库名称"}`
  - 校验：`name` 必填（`@NotBlank`）、最多 100 字符（`@Size(max = 100)`）。服务端在校验后裁剪首尾空白，允许多个知识库同名。
  - 成功：`201 Created`，返回 `Location: /api/v1/knowledge-bases/{baseId}` 与 `KnowledgeBaseResponse`。
- `GET /api/v1/knowledge-bases`：查询全部知识库。
  - 成功：`200 OK`，返回 `KnowledgeBaseResponse` 数组；无分页，顺序不保证。
- `GET /api/v1/knowledge-bases/{baseId}`：查询指定知识库。
  - 成功：`200 OK`，返回 `KnowledgeBaseResponse`；不存在返回 `404`。
- `PUT /api/v1/knowledge-bases/{baseId}`：重命名知识库。
  - 请求体与校验同创建接口；保留原状态与创建时间，更新修改时间。
  - 成功：`200 OK`，返回更新后的 `KnowledgeBaseResponse`。

`KnowledgeBaseResponse` 响应结构：

```json
{
  "id": "7d46fda250b64e29a67c104a1e989625",
  "name": "技术资料",
  "status": 1,
  "createAt": "2026-10-16T00:00:00.123456Z",
  "updateAt": "2026-10-16T00:00:00.123456Z"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 知识库业务 ID，32 位业务标识 |
| `name` | string | 知识库展示名称，最多 100 字符 |
| `status` | int | 知识库可用状态，1 表示正常可用 |
| `createAt` | string | 知识库创建时间（UTC ISO 8601） |
| `updateAt` | string | 知识库最后更新时间（UTC ISO 8601） |

### 知识文档

- `POST /api/v1/knowledge-bases/{baseId}/documents`：在知识库中登记已上传的源文件。
  - 请求体：`{"fileId": "6c36bda250b64e29a67c104a1e989624"}`
  - 校验：`fileId` 必填（`@NotBlank`）、最多 32 字符（`@Size(max = 32)`）。
  - 规则：服务端通过请求身份校验调用者对源文件的所有权（文件须未删除、`status=1`、上传完成）。登记仅建立文档登记关系，生成状态为 `pending` 的文档，不读取文件正文，不自动触发构建。同一库内重复登记同一文件返回 `409`（`DOCUMENT_ALREADY_REGISTERED`）。
  - 成功：`201 Created`，返回 `Location: /api/v1/knowledge-documents/{documentId}` 与 `KnowledgeDocumentResponse`。
- `GET /api/v1/knowledge-bases/{baseId}/documents`：查询指定知识库登记的全部文档。
  - 成功：`200 OK`，返回 `KnowledgeDocumentResponse` 数组。库不存在返回 `404`；库存在但无文档返回空数组 `[]`。无分页。
- `GET /api/v1/knowledge-documents/{documentId}`：查询指定文档状态与活动版本。
  - 成功：`200 OK`，返回 `KnowledgeDocumentResponse`。不展开正文或历史版本；不存在返回 `404`。

`KnowledgeDocumentResponse` 响应结构：

```json
{
  "id": "8a56cda250b64e29a67c104a1e989626",
  "knowledgeBaseId": "7d46fda250b64e29a67c104a1e989625",
  "fileId": "6c36bda250b64e29a67c104a1e989624",
  "status": 1,
  "indexStatus": "pending",
  "activeRevisionId": null,
  "lastFailureCode": null,
  "lastFailureMessage": null,
  "createAt": "2026-10-16T00:00:00.123456Z",
  "updateAt": "2026-10-16T00:00:00.123456Z"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 知识文档业务 ID，32 位业务标识 |
| `knowledgeBaseId` | string | 所属知识库 ID |
| `fileId` | string | 关联的已上传源文件 ID |
| `status` | int | 文档可用状态，1 表示正常可用 |
| `indexStatus` | string | 索引生命周期状态：`pending`、`indexed`、`failed`、`removed` |
| `activeRevisionId` | string 或 null | 当前生效的活动版本 ID；未发布或初始状态为 null |
| `lastFailureCode` | string 或 null | 最近一次构建失败的业务错误编码；无失败为 null |
| `lastFailureMessage` | string 或 null | 最近一次构建失败的脱敏摘要说明；无失败为 null |
| `createAt` | string | 文档登记创建时间（UTC ISO 8601） |
| `updateAt` | string | 文档记录最后更新时间（UTC ISO 8601） |

- `POST /api/v1/knowledge-documents/{documentId}/builds`：同步构建并发布文档新版本。
  - 请求体：可完全省略请求体（无 body）、提交 `{}` 或 `{"mode": null}`，均默认采用 `FLAT` 模式。显式提交时仅接受大写枚举 `FLAT` 或 `PARENT_CHILD`：
    ```json
    {"mode": "FLAT"}
    ```
    提交其他非枚举值或非法 JSON 时返回 `400`（`REQUEST_BODY_INVALID`）。
  - 同步执行与开销警告：构建为全同步流程，依次执行源文件读取、文档格式解析（DOCX/PDF 等，PDF 在启用云解析时调用阿里云文档智能）、Token 预算切分、切片数据库保存、检索切片向量化（OpenAI Embedding + Qdrant）以及版本发布。耗时取决于文档大小、格式及外部网络响应。**HTTP 请求超时或客户端连接断开不代表后端执行已撤销，重试前须核对状态**。每次成功调用都会产生新版本序号；若启用了云端解析还会重复消耗第三方云服务额度并可能产生费用。请求超时后应先通过文档或版本查询接口核对最新状态。
  - 依赖与可用性限制：服务端未启用 RAG（`app.knowledge.rag.enabled=false`）时返回 `503`（`KNOWLEDGE_RAG_DISABLED`）；源文件格式未注册对应解析器或分块模式未注册对应切分策略时返回 `503`（`KNOWLEDGE_PARSER_UNAVAILABLE` 或 `KNOWLEDGE_CHUNKER_UNAVAILABLE`）。文档正在构建时互斥拦截，返回 `409`（`BUILD_ALREADY_RUNNING`）。
  - 成功：`200 OK`，返回 `KnowledgeBuildResponse` 完成事实。

`KnowledgeBuildResponse` 响应结构：

```json
{
  "documentId": "8a56cda250b64e29a67c104a1e989626",
  "revisionId": "9b67dea250b64e29a67c104a1e989627",
  "chunkCount": 12,
  "retrievalChunkCount": 8,
  "oldRevisionCleanupCompleted": true
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `documentId` | string | 知识文档业务 ID |
| `revisionId` | string | 本次构建生成并已正式发布生效的文档版本 ID |
| `chunkCount` | int | 保存到数据库的全部切片片段总数（包含 CONTEXT 与 RETRIEVAL） |
| `retrievalChunkCount` | int | 实际写入外部向量库的检索片段数量（仅 RETRIEVAL） |
| `oldRevisionCleanupCompleted` | boolean | 无旧活动版本时为 true；否则仅在旧向量删除且清理事实写库成功时为 true，false 表示清理未完成或未确认，不影响新版本发布 |

### 文档版本（只读）

外部不能直接创建或更新版本，版本完全由构建流程在内部生命周期中管理。

- `GET /api/v1/knowledge-documents/{documentId}/revisions`：按版本序号升序查询指定文档的全部版本。
  - 包含 `building`（构建中）、`ready`（构建就绪）、`failed`（失败）、`superseded`（已被新版本替代）等全部生命周期状态。无分页。
  - 成功：`200 OK`，返回 `KnowledgeRevisionResponse` 数组。
- `GET /api/v1/knowledge-revisions/{revisionId}`：查询单个版本的审计与统计详情。
  - 成功：`200 OK`，返回 `KnowledgeRevisionResponse`；版本不存在返回 `404`。
  - 注意：`revisionStatus` 为 `ready` 仅表示该版本自身构建成功，不代表其一定是当前文档生效的活动版本。

`KnowledgeRevisionResponse` 响应结构：

```json
{
  "id": "9b67dea250b64e29a67c104a1e989627",
  "knowledgeDocumentId": "8a56cda250b64e29a67c104a1e989626",
  "revisionNumber": 1,
  "revisionStatus": "ready",
  "sourceSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "chunkCount": 12,
  "failureStage": null,
  "failureCode": null,
  "failureMessage": null,
  "createdAt": "2026-10-16T00:00:00.123456Z",
  "startedAt": "2026-10-16T00:00:00.123456Z",
  "completedAt": "2026-10-16T00:00:02.123456Z",
  "publishedAt": "2026-10-16T00:00:02.123456Z",
  "cleanedAt": null
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 文档版本业务 ID，32 位业务标识 |
| `knowledgeDocumentId` | string | 所属知识文档 ID |
| `revisionNumber` | int | 同一文档内单调递增的版本序号，从 1 开始 |
| `revisionStatus` | string | 版本生命周期状态：`building`、`ready`、`failed`、`superseded` |
| `sourceSha256` | string 或 null | 构建时实际读取校验的源文件 SHA-256 摘要；初始未计算时为 null |
| `chunkCount` | int | 当前版本包含的全部切片片段总数（包含 CONTEXT 与 RETRIEVAL） |
| `failureStage` | string 或 null | 失败发生阶段（如 SOURCE_READ、PARSE、CHUNK 等）；未失败为 null |
| `failureCode` | string 或 null | 构建失败业务错误编码；未失败为 null |
| `failureMessage` | string 或 null | 构建失败的脱敏摘要说明；未失败为 null |
| `createdAt` | string | 版本记录创建时间（UTC ISO 8601） |
| `startedAt` | string | 进入构建流程开始处理的时间（UTC ISO 8601） |
| `completedAt` | string 或 null | 构建流程结束时间（成功或失败）；尚未完成为 null |
| `publishedAt` | string 或 null | 正式发布为活动版本的时间；未发布或失败为 null |
| `cleanedAt` | string 或 null | 派生向量数据清理完成时间；未清理或无需清理为 null |

### 知识切片片段（只读）

切片片段由构建流程在 `CHUNK_SAVE` 阶段批量写入数据库；不提供手动批量写入（`saveBatch` 不暴露 HTTP）或修改接口。

- `GET /api/v1/knowledge-revisions/{revisionId}/chunks`：按全局序号升序查询指定版本的全部切片片段。
  - 重要定位说明：**本接口不是向量检索接口**（语义检索接口尚未实现）。本接口用于只读查看构建产生的完整片段事实，返回该版本下的所有角色片段（既包含用于检索的 `RETRIEVAL` 片段，也包含仅入库作为父上下文的 `CONTEXT` 片段），并支持查看任意状态版本（包括已被新版本替代的历史版本）。无分页。
  - 成功：`200 OK`，返回 `KnowledgeChunkResponse` 数组。版本不存在返回 `404`。
- `GET /api/v1/knowledge-chunks/{chunkId}`：查询单个切片详情。
  - 成功：`200 OK`，返回 `KnowledgeChunkResponse`；切片不存在返回 `404`。不自动展开父块或子块正文。

`KnowledgeChunkResponse` 响应结构：

```json
{
  "id": "0c78efa250b64e29a67c104a1e989628",
  "revisionId": "9b67dea250b64e29a67c104a1e989627",
  "parentChunkId": null,
  "chunkRole": "RETRIEVAL",
  "sequenceNo": 0,
  "content": "片段正文内容...",
  "headingPath": [
    {"level": 1, "title": "第一章 概述"}
  ],
  "tokenCount": 128,
  "startOffset": 0,
  "endOffset": 350,
  "pageStart": 1,
  "pageEnd": 1,
  "createAt": "2026-10-16T00:00:01.123456Z"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 切片片段业务 ID，32 位业务标识；RETRIEVAL 块在向量库中复用此标识 |
| `revisionId` | string | 所属文档版本 ID |
| `parentChunkId` | string 或 null | 父 CONTEXT 上下文块 ID；仅当本块为 RETRIEVAL 且存在父块时有值，无父块或本块为 CONTEXT 时为 null |
| `chunkRole` | string | 切片角色大写枚举：`CONTEXT`（上下文块）或 `RETRIEVAL`（检索块） |
| `sequenceNo` | int | 版本内非负且唯一的全局排序序号，两种角色共同占用；HTTP 通过 parentChunkId 表达父引用，不表示原文字符位置 |
| `content` | string | 处理后的切片片段正文文本内容 |
| `headingPath` | array 或 null | 有序标题路径数组；格式不支持大纲或无法提取时为 null，存在大纲解析上下文但当前无标题时为空数组 `[]` |
| `headingPath[].level` | int | 标题层级，从 1 开始的正整数（如 1 表示一级标题） |
| `headingPath[].title` | string | 标题文本；Markdown 去除块级标题标记但保留行内语法 |
| `tokenCount` | int 或 null | 基于本地分词器统计的正文 Token 数量；无法统计或未计算时为 null |
| `startOffset` | int 或 null | 规整后全文（统一 LF）中的 UTF-16 起始字符偏移量（包含该位置，从 0 开始）；无法可靠对齐时为 null |
| `endOffset` | int 或 null | 规整后全文中的 UTF-16 结束字符偏移量（不包含该位置，满足 startOffset < endOffset）；无法可靠对齐时为 null |
| `pageStart` | int 或 null | 来源文档起始页码（从 1 开始）；无分页概念或无法确定时为 null |
| `pageEnd` | int 或 null | 来源文档结束页码（满足 pageStart <= pageEnd）；无分页概念或无法确定时为 null |
| `createAt` | string | 切片记录创建时间（UTC ISO 8601） |

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

### 构建异常与 ProblemDetail 扩展

当文档构建流程失败（`KnowledgeBuildException`）时，错误响应会额外携带构建阶段与向量清理状态属性：

```json
{
  "type": "urn:problem:vector-write-failed",
  "title": "知识库构建失败",
  "status": 502,
  "detail": "检索片段向量写入失败",
  "instance": "/api/v1/knowledge-documents/document-id/builds",
  "code": "VECTOR_WRITE_FAILED",
  "requestId": "knowledge-failure",
  "stage": "VECTOR_WRITE",
  "cleanupCompleted": false
}
```

- `stage`：构建失败发生的内部阶段枚举大写字符串（`SOURCE_READ`、`PARSE`、`CHUNK`、`CHUNK_SAVE`、`VECTOR_WRITE`、`PUBLISH`）。
- `cleanupCompleted`：boolean 值，仅在失败版本的派生向量删除成功且清理事实写库成功时为 true；未清理或清理未确认时为 false。
- `detail` 严格固定为阶段的安全中文摘要，绝不暴露数据库底层原因、第三方供应商异常正文或堆栈信息。
- **发布结果保守边界**：当在 `PUBLISH` 阶段失败时，detail 固定为“知识版本发布失败，向量保留待核对”，此时检索向量可能已写入外部向量库，但数据库短事务发布失败，向量保留待核对，**系统不能声称或视为操作已回滚**。

### 状态码与错误码一览

| HTTP | code | 触发条件 |
| --- | --- | --- |
| 400 | `REQUEST_VALIDATION_ERROR` | 用户/知识库字段校验失败（如名称为空/超长、fileId 为空/超长、路径 ID 为空等） |
| 400 | `REQUEST_BODY_INVALID` | 缺少 JSON 请求体或 JSON 无法解析（如 build 提交了非枚举值的 mode） |
| 400 | `FILE_VALIDATION_ERROR` | 文件为空、名称/MIME/扩展名非法、业务大小超限、服务端 Key 超预算、存储类型缺失/非法/未启用或 multipart 格式错误 |
| 401 | `AUTHENTICATION_REQUIRED` | 文件或知识库接口缺少当前有效身份 |
| 404 | `RESOURCE_NOT_FOUND` | 请求的用户、文件、知识库、文档、版本或切片等资源不存在或不可访问 |
| 405 | `METHOD_NOT_ALLOWED` | 路径不支持所用方法；保留 `Allow` 等协议头 |
| 409 | `exception.code` | 业务冲突：库内文件重复登记（`DOCUMENT_ALREADY_REGISTERED`）、文档正在构建（`BUILD_ALREADY_RUNNING`）、版本号竞争冲突（`REVISION_NUMBER_CONFLICT`）等 |
| 413 | `FILE_SIZE_LIMIT_EXCEEDED` | 容器拦截单文件、整个请求或 part 头部超限 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 请求 Content-Type 不受支持，保留支持类型协议头 |
| 422 | `exception.code` | 构建解析或切分阶段拒绝输入（`PARSE` 阶段如 `PARSE_FAILED`、`CHUNK` 阶段如 `CHUNK_FAILED`，detail 为阶段固定安全摘要） |
| 500 | `exception.code` | 构建保存或发布阶段失败（`CHUNK_SAVE` 阶段如 `CHUNK_SAVE_FAILED`、`PUBLISH` 阶段如 `PUBLISH_FAILED`，detail 为阶段固定安全摘要，发布失败向量保留待核对不能说回滚） |
| 500 | `INTERNAL_ERROR` | 数据库或其他未预期的内部错误，detail 固定为“服务器内部错误” |
| 502 | `OBJECT_STORAGE_UNAVAILABLE` | 对象存储操作失败 |
| 502 | `exception.code` | 构建读取源文件（`SOURCE_READ` 阶段如 `SOURCE_READ_FAILED`）或写入向量库（`VECTOR_WRITE` 阶段如 `VECTOR_WRITE_FAILED`）外部后端失败 |
| 503 | `KNOWLEDGE_RAG_DISABLED` | 服务端未启用知识库 RAG 构建能力（`app.knowledge.rag.enabled=false`） |
| 503 | `KNOWLEDGE_PARSER_UNAVAILABLE` | 源文件格式没有注册对应的解析策略 |
| 503 | `KNOWLEDGE_CHUNKER_UNAVAILABLE` | 分块模式没有注册对应的切分策略 |
