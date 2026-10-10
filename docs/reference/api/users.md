# 用户接口

用户模块提供资料 CRUD，路径保持 `/api/users`。当前不读取请求身份，也不执行权限判断。运行前准备 MySQL 和用户表，见 [运行指南](../../guides/getting-started.md)。

返回 [接口总览](./README.md)。请求标识、模拟身份和错误结构见 [公共约定](./common.md)，内部实现见 [用户模块](../../modules/user.md)。

## 用户响应模型

创建、详情和修改都返回以下六个字段；列表中的每一项也采用该结构。

| 字段 | JSON 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 创建时生成的 32 位 UUID 字符串，不含连字符 |
| `name` | string | 去除首尾空白后的名称 |
| `email` | string | 保存的邮箱，保留大小写 |
| `status` | integer | 创建时为 `1`；资料更新不写此列，响应取读取时的值；删除保留原值，当前无状态修改接口 |
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

## Bash 示例准备

以下 Bash 示例可在项目启动后执行。示例中的密码是演示数据。查询、修改和删除时，将 `USER_ID` 替换为创建响应中的真实 `id`：

```bash
BASE_URL='http://localhost:8080'
USER_ID='替换为创建响应中的id'
```

## 创建用户

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

成功状态：`201 Created`，响应为 [用户对象](#用户响应模型)。示例名称保存为 `Alice`，密码中的首尾空格仍参与编码。

- `id`、`status` 和时间由服务生成，调用方不需要提交这些字段。
- 名称和邮箱允许重复，不因重复返回 `409`。
- 密码使用 BCrypt 编码，相同密码的摘要因随机盐而不同。
- 缺少字段、字段为空白、邮箱非法或长度超限：`400 / REQUEST_VALIDATION_ERROR`。
- 缺少请求体或 JSON 无法解析：`400 / REQUEST_BODY_INVALID`。
- 不支持的 Content-Type：`415 / UNSUPPORTED_MEDIA_TYPE`。
- 持久化失败等未处理异常：`500 / INTERNAL_ERROR`。

HTTP 字段校验先于服务裁剪。不要依赖邮箱的首尾空格被自动修正；例如 `" alice@example.test "` 会在 HTTP 校验时被拒绝。

## 查询用户列表

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

## 查询用户详情

**GET `/api/users/{id}`**

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | path | string | 是 | 用户 ID，服务裁剪首尾空白后查询 |

```bash
curl -i "$BASE_URL/api/users/$USER_ID"
```

成功状态：`200 OK`，响应为 [用户对象](#用户响应模型)。

- 不存在或已删除：`404 / RESOURCE_NOT_FOUND`。
- ID 全为空白：`400 / REQUEST_VALIDATION_ERROR`。
- 数据库读取失败等未处理异常：`500 / INTERNAL_ERROR`。

接口没有声明 UUID 格式校验。非空但不匹配记录的 ID 按资源不存在处理。

## 修改用户

**PUT `/api/users/{id}`**

路径参数 `id` 与详情接口相同。

请求体：

| 字段 | 类型 | 必填 | 校验和处理 |
| --- | --- | --- | --- |
| `name` | string | 是 | 与创建相同；每次修改都必须提交 |
| `email` | string | 是 | 与创建相同；每次修改都必须提交 |
| `password` | string 或 null | 否 | 省略或 `null` 时不更新数据库密码列；非空字符串按创建密码规则校验并重新编码 |

这是同时更新名称和邮箱的接口。即使只想修改密码，也必须提供名称和邮箱。

不修改密码的请求：

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

成功状态：`200 OK`，返回修改后的 [用户对象](#用户响应模型)。保留原 `id`，`status`、`createAt` 取本次读取的快照，刷新 `updateAt`。数据库更新集合只包含名称、邮箱、更新时间及明确提交的新密码摘要，不写状态、创建时间或删除标记。

| password 的提交方式 | 行为 |
| --- | --- |
| 省略字段 | 不更新密码列，保留数据库当前摘要 |
| `null` | 不更新密码列，保留数据库当前摘要 |
| `""` 或全空白字符串 | `400 / REQUEST_VALIDATION_ERROR` |
| 合法非空密码 | 重新生成 BCrypt 摘要 |
| 超过 72 个 UTF-8 字节或 HTTP 声明长度 | `400 / REQUEST_VALIDATION_ERROR` |

其他错误：字段校验失败返回 `400 / REQUEST_VALIDATION_ERROR`；请求体缺失或 JSON 无法解析返回 `400 / REQUEST_BODY_INVALID`；不支持的 Content-Type 返回 `415 / UNSUPPORTED_MEDIA_TYPE`；用户不存在或已删除返回 `404 / RESOURCE_NOT_FOUND`；数据库错误或写入影响行数不是 1 返回 `500 / INTERNAL_ERROR`。

## 删除用户

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

## 密码长度与并发边界

新密码同时受字符数和 UTF-8 字节数限制。BCrypt 的实际输入上限为 72 字节，例如 72 个 ASCII 字符或 24 个“密”；73 个 ASCII 字符或 25 个“密”会返回 `400`。超长密码不会被截断或预哈希。

省略或显式传 `null` 密码的资料更新不写密码列，因此不会用读取时的旧摘要覆盖已保存的新摘要。两个明确修改密码的请求或两个资料更新之间仍可能覆盖；此规则不提供完整并发保护。

修改和删除先查询，再写入，当前没有版本号或完整并发冲突治理。顺序重复删除返回 `404`；并发删除导致后续写入影响零行时，可能返回 `500`。接口没有承诺并发请求一定得到相同结果。

## PowerShell 调用示例

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

## 源码与测试入口

- [UserController](../../../src/main/java/com/item/aisummary/web/user/controller/UserController.java)：用户路径、方法和状态码。
- [CreateUserRequest](../../../src/main/java/com/item/aisummary/web/user/request/CreateUserRequest.java)、[UpdateUserRequest](../../../src/main/java/com/item/aisummary/web/user/request/UpdateUserRequest.java)：字段校验。
- [UserResponse](../../../src/main/java/com/item/aisummary/web/user/response/UserResponse.java)：六字段响应。
- [UserHttpTest](../../../src/test/java/com/item/aisummary/web/user/controller/UserHttpTest.java)：CRUD、输入边界、密码保留、响应字段和逻辑删除。
- [UserFailureHttpTest](../../../src/test/java/com/item/aisummary/web/user/controller/UserFailureHttpTest.java)：写入失败和内部信息隐藏。

测试命令与外部验收边界见 [测试说明](../../guides/testing.md)。
