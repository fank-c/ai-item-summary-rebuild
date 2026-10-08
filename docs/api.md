# API 文档

本文描述当前生产源码中已实现的 HTTP 接口：健康检查和用户资料 CRUD（创建、查询、修改、删除）。对话、摘要生成和 RAG 尚未提供 HTTP 接口。

## 1. 调用约定

- 默认服务地址：`http://localhost:8080`，端口可通过 `SERVER_PORT` 覆盖。
- 建表、数据库环境变量和启动命令见 [README](../README.md#建表与启动)。用户接口需要可用的 MySQL 和用户表。
- 创建、修改请求使用 `Content-Type: application/json`。
- 有响应体的成功请求返回 `application/json`；删除成功返回空响应体。
- 公共异常处理器返回 `application/problem+json`，结构见 [错误响应](#6-错误响应)。
- 成功响应直接返回对象或数组，没有 `code/data` 包装。
- 当前接口不要求 Authorization、Cookie 或 Session，不执行角色和资源所有权校验。

### 请求标识

可选请求头 `X-Request-Id` 用于关联请求和错误。普通响应会在同名响应头中返回标识，错误体可取得标识时也会包含 `requestId`。

| 输入 | 处理方式 |
| --- | --- |
| 1～64 个字符，首字符为英文字母或数字，其余仅为英文字母、数字、`.`、`_`、`-` | 保留客户端标识 |
| 未提供，或不符合上述规则 | 生成 UUID 标识 |

合法格式对应正则表达式 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`。例如 `X-Request-Id: api-demo-001`。

### 模拟身份

默认模拟身份为 `mock-user`，只注入 `/api/**` 的非 `OPTIONS` 请求，请求结束后清理。模拟机制忽略 Authorization，不解析 JWT、Cookie 或 Session，也不创建数据库用户。

用户 CRUD 不读取该身份。即使设置 `APP_AUTH_MOCK_ENABLED=false`，这些接口仍可访问。创建用户后，模拟身份也不会切换到新用户。

`LoginUserContext` 和 `UserReferenceService` 是内部调用能力，当前没有对应的 HTTP 接口。测试源码中的 `/api/test/**`、`/outside/test/**` 仅用于测试，不属于公开 API。

## 2. 接口总览

| 方法 | 路径 | 成功状态 | 响应 | 用途 |
| --- | --- | --- | --- | --- |
| GET | `/api/health` | `200` | 状态对象 | 检查 HTTP 服务是否可达 |
| POST | `/api/users` | `201` | 用户对象 | 创建资料和密码摘要 |
| GET | `/api/users` | `200` | 用户数组 | 查询所有未删除用户 |
| GET | `/api/users/{id}` | `200` | 用户对象 | 按 ID 查询未删除用户 |
| PUT | `/api/users/{id}` | `200` | 用户对象 | 更新名称、邮箱和可选密码 |
| DELETE | `/api/users/{id}` | `204` | 无响应体 | 逻辑删除用户 |

## 3. 用户响应模型

创建、详情和修改都返回以下六个字段；列表中的每一项也采用该结构。

| 字段 | JSON 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 创建时生成的 32 位 UUID 字符串，不含连字符 |
| `name` | string | 去除首尾空白后的名称 |
| `email` | string | 保存的邮箱，保留大小写 |
| `status` | integer | 创建时为 `1`；修改和删除保留原值，当前无状态修改接口 |
| `createAt` | string | UTC 时间，ISO 8601 格式，以 `Z` 结尾 |
| `updateAt` | string | UTC 时间，ISO 8601 格式，以 `Z` 结尾 |

示例中的 ID 和时间仅作说明，实际值以接口返回为准：

```json
{
  "id": "7d46fda250b64e29a67c104a1e989625",
  "name": "Alice",
  "email": "Alice@example.test",
  "status": 1,
  "createAt": "2026-10-07T00:00:00.123456Z",
  "updateAt": "2026-10-07T00:00:00.123456Z"
}
```

时间以微秒精度保存；字符串的小数位数可能因值而不同。创建时 `createAt` 与 `updateAt` 相同，修改时保留 `createAt` 并刷新 `updateAt`。

响应不包含密码、BCrypt 摘要或删除时间，也不返回其他业务资源。

## 4. 健康检查

### GET `/api/health`

无路径参数、查询参数或请求体。

```bash
curl -i http://localhost:8080/api/health
```

成功状态：`200 OK`。

```json
{"status":"UP"}
```

该接口返回应用状态，不执行数据库、模型服务或其他外部依赖探测。`UP` 不代表这些外部依赖已验证可用。

## 5. 用户接口

以下 Bash 示例可在项目启动后执行。示例中的密码是演示数据。查询、修改和删除时，将 `USER_ID` 替换为创建响应中的真实 `id`：

```bash
BASE_URL='http://localhost:8080'
USER_ID='替换为创建响应中的id'
```

### 5.1 创建用户

**POST `/api/users`**

请求体：

| 字段 | 类型 | 必填 | 校验和处理 |
| --- | --- | --- | --- |
| `name` | string | 是 | 非空白，最多 100 字符；通过 HTTP 校验后裁剪首尾空白 |
| `email` | string | 是 | 非空白，邮箱格式合法，最多 254 字符；通过 HTTP 校验后裁剪首尾空白，保留大小写 |
| `password` | string | 是 | 非空白，HTTP 声明上限 255 字符，同时不得超过 72 个 UTF-8 字节；保留首尾空格 |

```json
{
  "name": " Alice ",
  "email": "Alice@example.test",
  "password": " demo-password "
}
```

```bash
curl -i -X POST "$BASE_URL/api/users" \
  -H 'Content-Type: application/json' \
  -H 'X-Request-Id: api-demo-001' \
  -d '{"name":" Alice ","email":"Alice@example.test","password":" demo-password "}'
```

成功状态：`201 Created`，响应为 [用户对象](#3-用户响应模型)。示例名称保存为 `Alice`，密码中的首尾空格仍参与编码。

- `id`、`status` 和时间由服务生成，调用方不需要提交这些字段。
- 名称和邮箱允许重复，不因重复返回 `409`。
- 密码使用 BCrypt 编码，相同密码的摘要因随机盐而不同。
- 缺少字段、字段为空白、邮箱非法或长度超限：`400 / REQUEST_VALIDATION_ERROR`。
- 缺少请求体或 JSON 无法解析：`400 / REQUEST_BODY_INVALID`。
- 持久化失败等未处理异常：`500 / INTERNAL_ERROR`。

HTTP 字段校验先于服务裁剪。不要依赖邮箱的首尾空格被自动修正；例如 `" alice@example.test "` 会在 HTTP 校验时被拒绝。

### 5.2 查询用户列表

**GET `/api/users`**

无请求体。当前没有分页、搜索、排序或状态过滤参数。

```bash
curl -i "$BASE_URL/api/users"
```

成功状态：`200 OK`，响应为用户数组：

```json
[
  {
    "id": "7d46fda250b64e29a67c104a1e989625",
    "name": "Alice",
    "email": "Alice@example.test",
    "status": 1,
    "createAt": "2026-10-07T00:00:00.123456Z",
    "updateAt": "2026-10-07T00:00:00.123456Z"
  }
]
```

没有未删除用户时返回 `[]`。只过滤逻辑删除记录，不按 `status` 过滤；响应顺序没有保证。数据库读取失败等未处理异常返回 `500 / INTERNAL_ERROR`。

### 5.3 查询用户详情

**GET `/api/users/{id}`**

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | path | string | 是 | 用户 ID，服务裁剪首尾空白后查询 |

```bash
curl -i "$BASE_URL/api/users/$USER_ID"
```

成功状态：`200 OK`，响应为 [用户对象](#3-用户响应模型)。

- 不存在或已删除：`404 / RESOURCE_NOT_FOUND`。
- ID 全为空白：`400 / REQUEST_VALIDATION_ERROR`。
- 数据库读取失败等未处理异常：`500 / INTERNAL_ERROR`。

接口没有声明 UUID 格式校验。非空但不匹配记录的 ID 按资源不存在处理。

### 5.4 修改用户

**PUT `/api/users/{id}`**

路径参数 `id` 与详情接口相同。

请求体：

| 字段 | 类型 | 必填 | 校验和处理 |
| --- | --- | --- | --- |
| `name` | string | 是 | 与创建相同；每次修改都必须提交 |
| `email` | string | 是 | 与创建相同；每次修改都必须提交 |
| `password` | string 或 null | 否 | 省略或 `null` 保留旧摘要；非空字符串按创建密码规则校验并重新编码 |

这是同时更新名称和邮箱的接口。即使只想修改密码，也必须提供名称和邮箱。

保留旧密码的请求：

```json
{
  "name": " Bob ",
  "email": "Bob@example.test"
}
```

```bash
curl -i -X PUT "$BASE_URL/api/users/$USER_ID" \
  -H 'Content-Type: application/json' \
  -d '{"name":" Bob ","email":"Bob@example.test"}'
```

修改密码的请求：

```json
{
  "name": "Bob",
  "email": "Bob@example.test",
  "password": "new-demo-password"
}
```

```bash
curl -i -X PUT "$BASE_URL/api/users/$USER_ID" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Bob","email":"Bob@example.test","password":"new-demo-password"}'
```

成功状态：`200 OK`，返回修改后的 [用户对象](#3-用户响应模型)。保留原 `id`、`status`、`createAt`，刷新 `updateAt`。

| password 的提交方式 | 行为 |
| --- | --- |
| 省略字段 | 保留旧摘要 |
| `null` | 保留旧摘要 |
| `""` 或全空白字符串 | `400 / REQUEST_VALIDATION_ERROR` |
| 合法非空密码 | 重新生成 BCrypt 摘要 |
| 超过 72 个 UTF-8 字节或 HTTP 声明长度 | `400 / REQUEST_VALIDATION_ERROR` |

其他错误：字段校验失败返回 `400 / REQUEST_VALIDATION_ERROR`；请求体缺失或 JSON 无法解析返回 `400 / REQUEST_BODY_INVALID`；用户不存在或已删除返回 `404 / RESOURCE_NOT_FOUND`；数据库错误或写入影响行数不是 1 返回 `500 / INTERNAL_ERROR`。

### 5.5 删除用户

**DELETE `/api/users/{id}`**

路径参数 `id` 与详情接口相同，无请求体。

```bash
curl -i -X DELETE "$BASE_URL/api/users/$USER_ID"
```

成功状态：`204 No Content`，响应体为空，调用方不应对成功响应执行 JSON 解析。

- 删除采用逻辑删除：数据库写入删除时间，保留物理记录、资料、密码摘要和原 `status`。
- 删除后列表不再显示该用户，详情、修改和再次删除均返回 `404 / RESOURCE_NOT_FOUND`。
- ID 全为空白返回 `400 / REQUEST_VALIDATION_ERROR`。
- 不清理用户关联的其他业务资源。
- 数据库错误或写入影响行数不是 1 返回 `500 / INTERNAL_ERROR`。

### 密码长度与并发边界

新密码同时受字符数和 UTF-8 字节数限制。BCrypt 的实际输入上限为 72 字节，例如 72 个 ASCII 字符或 24 个“密”；73 个 ASCII 字符或 25 个“密”会返回 `400`。超长密码不会被截断或预哈希。

修改和删除先查询，再写入，当前没有版本号或完整并发冲突治理。顺序重复删除返回 `404`；并发删除导致后续写入影响零行时，可能返回 `500`。接口没有承诺并发请求一定得到相同结果。

## 6. 错误响应

公共错误采用 Spring `ProblemDetail`，即用统一 JSON 表达错误状态、说明和请求位置。

| 字段 | JSON 类型 | 说明 |
| --- | --- | --- |
| `type` | string | `urn:problem:` 加错误码的小写连字符形式 |
| `title` | string | 错误类别的中文标题 |
| `status` | integer | 对应 HTTP 状态码 |
| `detail` | string | 安全的错误说明 |
| `instance` | string | 当前请求 URI 路径，例如 `/api/users` |
| `code` | string | 用于程序判断的稳定错误码 |
| `requestId` | string | 可取得时返回，与响应头 `X-Request-Id` 对应 |
| `errors` | array | HTTP 字段校验失败时返回，项中仅含 `field`、`message`；其他错误不保证包含 |

### 错误码

| HTTP 状态 | code | title | 当前触发条件 |
| --- | --- | --- | --- |
| `400` | `REQUEST_VALIDATION_ERROR` | 请求参数错误 | 字段校验失败，或服务参数非法，例如空白密码、超过 72 字节的密码、空白 ID |
| `400` | `REQUEST_BODY_INVALID` | 请求体错误 | 请求体缺失或 JSON 无法解析 |
| `404` | `RESOURCE_NOT_FOUND` | 资源不存在 | 用户不存在、已删除，或没有对应静态资源/接口路径 |
| `401` | `AUTHENTICATION_REQUIRED` | 需要身份 | 调用内部需要当前身份的能力时未取得身份；当前公开健康检查和用户 CRUD 不读取身份 |
| `500` | `INTERNAL_ERROR` | 服务器内部错误 | 数据库异常、写入结果异常或其他未处理异常 |

本表描述当前公共异常处理器的映射。调用方应以 HTTP 状态和 `code` 判断错误，不依赖 `detail` 文本或 `errors` 的顺序。

### 字段校验错误

创建时提交合法名称、邮箱但省略密码，并使用请求头 `X-Request-Id: api-demo-002`：

```json
{
  "name": "Alice",
  "email": "alice@example.test"
}
```

返回 `400 Bad Request`，响应示例：

```json
{
  "type": "urn:problem:request-validation-error",
  "title": "请求参数错误",
  "status": 400,
  "detail": "请求参数校验失败",
  "instance": "/api/users",
  "code": "REQUEST_VALIDATION_ERROR",
  "requestId": "api-demo-002",
  "errors": [
    {"field": "password", "message": "密码不能为空"}
  ]
}
```

`errors` 不回显提交的密码或其他原始输入。密码字节超限、修改时空白密码等服务层参数错误也使用该错误码，但没有字段级 `errors` 数组。

### 资源不存在

请求 `GET /api/users/missing`，并使用请求头 `X-Request-Id: api-demo-003`，返回 `404 Not Found`：

```json
{
  "type": "urn:problem:resource-not-found",
  "title": "资源不存在",
  "status": 404,
  "detail": "请求资源不存在",
  "instance": "/api/users/missing",
  "code": "RESOURCE_NOT_FOUND",
  "requestId": "api-demo-003"
}
```

### 内部错误

内部错误返回 `500 Internal Server Error`，`detail` 固定为“服务器内部错误”，不向调用方暴露 SQL、密码或内部异常信息。例如创建失败：

```json
{
  "type": "urn:problem:internal-error",
  "title": "服务器内部错误",
  "status": 500,
  "detail": "服务器内部错误",
  "instance": "/api/users",
  "code": "INTERNAL_ERROR",
  "requestId": "api-demo-004"
}
```

## 7. PowerShell 调用示例

Windows PowerShell 中使用 `Invoke-RestMethod` 可避免 Bash 多行语法和 `curl` 别名差异。以下示例会创建一条演示记录，查询和修改后将其逻辑删除。

```powershell
$apiBase = 'http://localhost:8080'
Invoke-RestMethod -Method Get -Uri "$apiBase/api/health"

$createBody = @{
    name = 'Alice'
    email = 'alice@example.test'
    password = 'demo-password'
} | ConvertTo-Json

$createdUser = Invoke-RestMethod -Method Post -Uri "$apiBase/api/users" `
    -ContentType 'application/json' -Body $createBody
$userId = $createdUser.id

Invoke-RestMethod -Method Get -Uri "$apiBase/api/users"
Invoke-RestMethod -Method Get -Uri "$apiBase/api/users/$userId"

$updateBody = @{
    name = 'Bob'
    email = 'bob@example.test'
} | ConvertTo-Json

Invoke-RestMethod -Method Put -Uri "$apiBase/api/users/$userId" `
    -ContentType 'application/json' -Body $updateBody

Invoke-RestMethod -Method Delete -Uri "$apiBase/api/users/$userId"
```

## 8. 实现依据与维护

接口声明和校验依据：

- [HealthController](../src/main/java/com/item/aisummary/web/HealthController.java)：健康检查。
- [UserController](../src/main/java/com/item/aisummary/web/user/UserController.java)：用户路径、方法和状态码。
- [CreateUserRequest](../src/main/java/com/item/aisummary/web/user/CreateUserRequest.java)、[UpdateUserRequest](../src/main/java/com/item/aisummary/web/user/UpdateUserRequest.java)：字段校验。
- [UserResponse](../src/main/java/com/item/aisummary/web/user/UserResponse.java)：六字段响应。
- [GlobalExceptionHandler](../src/main/java/com/item/aisummary/web/error/GlobalExceptionHandler.java)、[RequestIdFilter](../src/main/java/com/item/aisummary/infrastructure/web/RequestIdFilter.java)：错误和请求标识。

行为测试依据：

- [UserHttpTest](../src/test/java/com/item/aisummary/web/user/UserHttpTest.java)：CRUD、输入边界、密码保留、响应字段和逻辑删除。
- [UserFailureHttpTest](../src/test/java/com/item/aisummary/web/user/UserFailureHttpTest.java)：写入失败和内部信息隐藏。
- [MockAuthenticationDisabledWebTest](../src/test/java/com/item/aisummary/infrastructure/auth/MockAuthenticationDisabledWebTest.java)：关闭模拟身份后用户接口仍可访问。

修改或新增公开接口时，同步维护本文件和 [README](../README.md)，并按 [AGENTS.md](../AGENTS.md) 验证路径、状态码和响应结构。
