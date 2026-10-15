# 公共约定与错误响应

返回 [接口总览](./README.md)或[文档导航](../../README.md)。业务参数与成功响应分别见 [用户接口](./users.md)和[文件接口](./files.md)。

## 调用约定

- 默认服务地址：`http://localhost:8080`，端口可通过 `SERVER_PORT` 覆盖。
- 建表、数据库环境变量和启动命令见 [运行指南](../../guides/getting-started.md)。用户接口需要可用的 MySQL 和用户表。
- 用户创建、修改请求使用 `Content-Type: application/json`；文件上传使用 `multipart/form-data`。不支持的请求媒体类型返回 `415 / UNSUPPORTED_MEDIA_TYPE`。
- 有响应体的成功请求返回 `application/json`；删除成功返回空响应体。
- 公共异常处理器返回 `application/problem+json`，结构见 [错误响应](#错误响应)。
- 成功响应直接返回对象或数组，没有 `code/data` 包装。
- 当前健康检查和用户接口不要求身份。文件接口读取服务端请求身份，并检查所有权；模拟身份不解析 Authorization、Cookie 或 Session。

## 请求标识

可选请求头 `X-Request-Id` 用于关联请求和错误。普通响应会在同名响应头中返回标识，错误体可取得标识时也会包含 `requestId`。

| 输入 | 处理方式 |
| --- | --- |
| 1～64 个字符，首字符为英文字母或数字，其余仅为英文字母、数字、`.`、`_`、`-` | 保留客户端标识 |
| 未提供，或不符合上述规则 | 生成 UUID 标识 |

合法格式对应正则表达式 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`。例如 `X-Request-Id: api-demo-001`。

## 模拟身份

默认模拟身份为 `mock-user`，只注入 `/api/**` 的非 `OPTIONS` 请求，请求结束后清理。模拟机制忽略 Authorization，不解析 JWT、Cookie 或 Session，也不创建数据库用户。

启用模拟时，配置的用户 ID 裁剪后必须非空且最多 64 个 Unicode 码点；超长配置拒绝启动。补充平面字符计 1 个码点。关闭模拟时不校验未使用的 ID 长度。该配置由服务端管理，文件请求不增加客户端 userId 参数。

用户 CRUD 不读取该身份。即使设置 `APP_AUTH_MOCK_ENABLED=false`，用户接口仍可访问；文件三个接口此时返回 `401 AUTHENTICATION_REQUIRED`。创建用户后，模拟身份不会切换，文件仍归属于配置用户 ID。文件服务不额外查询用户表。

`LoginUserContext` 和 `UserReferenceService` 是内部调用能力，当前没有对应的 HTTP 接口。测试源码中的 `/api/test/**`、`/outside/test/**` 仅用于测试，不属于公开 API。身份配置见 [配置说明](../configuration.md#模拟身份)，内部实现见 [工程结构](../../architecture.md#请求身份与用户引用)。

## 错误响应

公共错误采用 Spring `ProblemDetail`，即用统一 JSON 表达错误状态、说明和请求位置。

| 字段 | JSON 类型 | 说明 |
| --- | --- | --- |
| `type` | string | `urn:problem:` 加错误码的小写连字符形式 |
| `title` | string | 错误类别的中文标题 |
| `status` | integer | 对应 HTTP 状态码 |
| `detail` | string | 安全的错误说明 |
| `instance` | string | 当前请求 URI 路径，例如 `/api/users`；不含查询字符串 |
| `code` | string | 用于程序判断的稳定错误码 |
| `requestId` | string | 可取得时返回，与响应头 `X-Request-Id` 对应 |
| `errors` | array | HTTP 字段校验失败时返回，项中仅含 `field`、`message`；其他错误不保证包含 |

### 错误码

| HTTP 状态 | code | title | 当前触发条件 |
| --- | --- | --- | --- |
| `400` | `REQUEST_VALIDATION_ERROR` | 请求参数错误 | 字段校验失败，或服务参数非法，例如空白密码、超过 72 字节的密码、空白 ID |
| `400` | `REQUEST_BODY_INVALID` | 请求体错误 | 请求体缺失或 JSON 无法解析 |
| `404` | `RESOURCE_NOT_FOUND` | 资源不存在 | 用户不存在或已删除；文件不存在、删除、停用、未完成、非所有者或存储类型不可用；没有对应资源路径 |
| `401` | `AUTHENTICATION_REQUIRED` | 需要身份 | 文件接口或其他需要当前身份的入口未取得身份；健康检查和用户 CRUD 不读取身份 |
| `400` | `FILE_VALIDATION_ERROR` | 文件参数错误 | 空文件、名称或扩展名非法、业务大小超限、新 Key 超过 512 长度、1023 个 UTF-8 总字节或单个路径段 255 个 UTF-8 字节、存储类型缺失/非法/未启用、multipart 格式错误 |
| `413` | `FILE_SIZE_LIMIT_EXCEEDED` | 文件超过大小限制 | 容器提前拦截单文件、整个 multipart 请求或单个 part 头部超限 |
| `405` | `METHOD_NOT_ALLOWED` | 请求方法不支持 | 路径存在但不支持请求方法，例如 GET `/api/files/{fileId}` |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | 请求媒体类型不支持 | 用户创建/修改或文件上传的 Content-Type 不受支持 |
| `502` | `OBJECT_STORAGE_UNAVAILABLE` | 对象存储不可用 | SDK 上传、读取、签名或删除失败 |
| `500` | `INTERNAL_ERROR` | 服务器内部错误 | 数据库异常、写入结果异常或其他未处理异常 |

本表描述当前公共异常处理器的映射。调用方应以 HTTP 状态和 `code` 判断错误，不依赖 `detail` 文本或 `errors` 的顺序。405 响应保留框架异常提供的 `Allow` 等协议头。

### 请求媒体类型不支持

用户创建/修改发送 `text/plain`，或文件上传发送 `application/json`，返回 `415 Unsupported Media Type`。响应保留异常提供的支持类型协议头（例如 `Accept`），不回显请求体或框架异常消息。文件入口示例：

```json
{
  "type": "urn:problem:unsupported-media-type",
  "title": "请求媒体类型不支持",
  "status": 415,
  "detail": "请求的 Content-Type 不受支持",
  "instance": "/api/files",
  "code": "UNSUPPORTED_MEDIA_TYPE",
  "requestId": "media-type-review"
}
```

`requestId` 沿用公共过滤器的标识。声明 JSON 但内容无法解析仍返回 `400 / REQUEST_BODY_INVALID`。

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

## 源码与测试入口

- [GlobalExceptionHandler](../../../src/main/java/com/item/aisummary/web/error/GlobalExceptionHandler.java)、[RequestIdFilter](../../../src/main/java/com/item/aisummary/infrastructure/web/RequestIdFilter.java)：错误和请求标识。
- [GlobalExceptionHandlerHttpTest](../../../src/test/java/com/item/aisummary/web/error/GlobalExceptionHandlerHttpTest.java)：用户创建、修改与文件上传的 415 字段、协议头和请求标识用例。
- [MockAuthenticationDisabledWebTest](../../../src/test/java/com/item/aisummary/infrastructure/auth/web/MockAuthenticationDisabledWebTest.java)：关闭模拟身份后用户接口仍可访问。

测试命令与验证边界见 [测试说明](../../guides/testing.md)。
