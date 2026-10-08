# AI Item Summary

基于 Spring Boot 和 Spring AI 的学习工程。当前包含健康检查、用户资料 CRUD、BCrypt 密码摘要、跨模块用户引用及模拟请求身份。对话和 RAG 按后续学习需求加入。

开发前阅读 [项目规则](./AGENTS.md)。用户模块设计和执行记录位于 [设计文档](./docs/superpowers/specs/2026-10-07-user-module-design.md) 和 [实现计划](./docs/superpowers/plans/2026-10-07-user-module.md)。

当前 HTTP 接口的请求参数、响应示例、错误码和 Bash / PowerShell 调用方式见 [API 文档](./docs/api.md)。

## 学习目标与结构

本模块练习 Spring MVC 请求映射、Jakarta Validation、`ProblemDetail`、MyBatis-Plus UUID/逻辑删除、`BCryptPasswordEncoder`、配置条件装配和 MVC 请求属性身份。用户 CRUD 不调用 AI 模型，不需要模型 API Key。

单个 Maven 工程的四层职责如下：

| 包 | 职责 |
| --- | --- |
| `web.user`、`web.error` | HTTP 校验、请求/响应转换和公共错误映射 |
| `application.user`、`application.auth` | 用户用例、用户引用与最小身份合同；通过 port 调用外部能力 |
| `domain.user` | 用户数据与文本规则，不依赖 Web、MyBatis 或 Spring AI |
| `infrastructure.user`、`infrastructure.security`、`infrastructure.auth`、`infrastructure.web` | 数据库、BCrypt、模式切换、请求身份和请求标识 |

依赖方向为 `web → application → domain`，基础设施实现应用层接口。HTTP DTO 不进入应用服务，数据库实体不进入领域层。未来 Prompt 模板放在 `src/main/resources/prompts/`。

## 技术基线

- Java 21、Maven 3.9+
- Spring Boot 4.1.1、Spring AI 2.0.1
- MyBatis-Plus 3.5.17，使用 `mybatis-plus-spring-boot4-starter`
- MySQL；驱动、Validation 和 Spring Security Crypto 版本由 Boot 管理
- H2 仅用于普通自动测试；运行时没有内存数据库回退

## 建表与启动

先准备 MySQL 数据库，例如 `ai_item_summary`。在项目根目录用有建表权限的账户进入选定数据库：

```bash
mysql -h 127.0.0.1 -u your_user -p ai_item_summary
```

在 MySQL 客户端执行：

```sql
SOURCE deploy/mysql/schema.sql;
```

应用不自动建表或修改已有数据。`schema.sql` 只包含用户表；名称和邮箱没有唯一约束。

配置数据库连接后启动。PowerShell 示例：

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/ai_item_summary?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:DB_USERNAME = 'your_user'
# 在本地环境中提供 DB_PASSWORD；不要把真实密码写入仓库。
mvn spring-boot:run
```

Bash 使用同名环境变量：

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3306/ai_item_summary?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
export DB_USERNAME='your_user'
# 在本地环境中提供 DB_PASSWORD。
mvn spring-boot:run
```

数据库连接和会话应采用 UTC。覆盖 `DB_URL` 时保留示例中的两个时区参数，否则数据库生成的删除时间可能不是 UTC。类型处理器将 `DATETIME(6)` 显式映射为 UTC `Instant`，不依赖 JVM 默认时区。

健康检查：

```bash
curl http://localhost:8080/api/health
```

预期 `200`，响应 `{"status":"UP"}`。模型默认关闭，不需要 API Key；用户接口需要可用的数据库和用户表。

## 环境变量

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/ai_item_summary?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` | 数据库连接及 UTC 会话 |
| `DB_USERNAME` | 空 | 数据库账户，运行前设置 |
| `DB_PASSWORD` | 空 | 数据库凭据，通过本地环境提供 |
| `APP_AUTH_MOCK_ENABLED` | `true` | 固定请求身份和固定用户引用开关 |
| `APP_AUTH_MOCK_USER_ID` | `mock-user` | 固定用户 ID |
| `APP_AUTH_MOCK_USERNAME` | `mock-user` | 固定展示名称 |
| `SERVER_PORT` | `8080` | HTTP 端口 |
| `SPRING_AI_MODEL_CHAT` | `none` | 设为 `openai` 启用模型 |
| `OPENAI_API_KEY` | 空 | 模型凭据 |
| `OPENAI_MODEL` | `gpt-5-mini` | 模型名称 |

## 用户接口与输入输出

成功响应直接返回用户对象或数组，没有 `code/data` 包装。

| 方法与路径 | 成功状态 | 行为 |
| --- | --- | --- |
| `POST /api/users` | `201` | 创建资料和 BCrypt 摘要 |
| `GET /api/users` | `200` | 全部未删除用户，无分页、搜索或排序承诺 |
| `GET /api/users/{id}` | `200` | 查询未删除用户 |
| `PUT /api/users/{id}` | `200` | 同时修改名称和邮箱，可选修改密码 |
| `DELETE /api/users/{id}` | `204` | 逻辑删除，无响应体 |

创建示例中的密码仅为演示输入：

```bash
curl -i -X POST http://localhost:8080/api/users \
  -H 'Content-Type: application/json' \
  -d '{"name":" Alice ","email":"Alice@example.test","password":" demo-password "}'
```

响应字段固定为：

```json
{
  "id": "32位UUID",
  "name": "Alice",
  "email": "Alice@example.test",
  "status": 1,
  "createAt": "2026-10-07T00:00:00.123456Z",
  "updateAt": "2026-10-07T00:00:00.123456Z"
}
```

输入规则：

- 创建和修改都要求名称非空白、最多 100 字符；邮箱非空白、格式合法、最多 254 字符。
- HTTP 字段校验先执行，服务随后裁剪名称、邮箱和 ID 的首尾空白。带空格邮箱可能在裁剪前被 HTTP 校验拒绝；邮箱不转小写。
- 创建密码必填、非空白。修改省略密码或传 `null` 保留旧摘要；空字符串及全空白返回 `400`。
- 密码首尾空格保留。HTTP 声明上限仍为 255 字符，实际新密码还必须不超过 72 个 UTF-8 字节，例如最多 72 个 ASCII 字符或 24 个“密”。超限返回 `400`，不截断或预哈希。
- 用户响应不包含密码、摘要或删除时间。相同密码多次编码使用不同随机盐。
- 不存在和已删除用户的详情、修改、删除返回 `404`。删除保留物理记录、状态和资料，不清理其他业务资源。
- 创建默认 `status=1`，本阶段没有状态流转。查询和引用不依据 `status` 过滤。
- 写入影响行数必须为 1。没有版本号或完整并发冲突治理；并发删除可能因零行更新返回 `500`，顺序重复删除返回 `404`。

## 模拟身份与跨模块调用

默认只为 `/api/**` 的非 `OPTIONS` 请求注入固定身份，请求结束后清理。身份保存在请求属性中，HTTP 调用方通过 `LoginUserContext.require(request)` 读取，再向应用用例显式传入必要的用户数据。未取得身份时返回 `401`。

模拟机制不解析 Authorization、JWT、Cookie 或 Session，不执行权限判断，不把固定用户写入数据库。创建用户不会切换模拟身份。

`UserReferenceService.requireExisting(userId)` 返回 `id` 和 `name`：

| 模式 | 判断方式 |
| --- | --- |
| 模拟开启 | 只接受配置用户 ID，不访问用户表；不匹配抛参数异常 |
| 模拟关闭 | 查询未删除的用户，不存在或已删除抛用户不存在异常；不检查 status |

用户管理接口本身不读取当前身份，不执行角色或所有权校验。关闭模拟机制后 `/api/users` 仍可访问。真实登录、退出、角色权限和账户启停不在当前实现范围。

## 错误响应

错误使用 `application/problem+json`，字段为 `type`、`title`、`status`、`detail`、`instance`、`code`、`requestId`。响应头带 `X-Request-Id`；合法客户端标识会被保留，其余情况生成新值。

| 情况 | 状态 | code |
| --- | --- | --- |
| 字段校验或业务参数非法 | `400` | `REQUEST_VALIDATION_ERROR` |
| 请求体缺失、JSON 无法解析 | `400` | `REQUEST_BODY_INVALID` |
| 用户不存在或已删除 | `404` | `RESOURCE_NOT_FOUND` |
| 依赖当前身份但未取得身份 | `401` | `AUTHENTICATION_REQUIRED` |
| 写入结果异常或其他未处理错误 | `500` | `INTERNAL_ERROR` |

字段校验还包含 `errors` 数组，每项只有 `field` 和 `message`，不回显原始输入。服务参数错误不一定有 `errors`。未知异常 detail 固定为“服务器内部错误”；SQL 参数日志关闭，公共错误日志只记录请求标识与异常类型。

Boot 4 的默认错误序列化可能省略默认 type。本工程显式使用 `urn:problem:<错误码的小写连字符形式>`，确保每个错误响应都带 type。

## 构建与测试

```bash
mvn clean verify
```

普通测试使用独立 H2 数据库，并强制关闭模型，不连接本机 MySQL、Docker、模型服务或个人凭据。测试包含基础上下文、健康检查、完整用户 HTTP 流程、服务失败边界、密码兼容、真实 Mapper SQL、引用切换和身份清理。

真实 MySQL 验收入口单独开启。准备数据库名以 `_test` 结尾的专用测试库，再设置：

```powershell
$env:USER_TEST_DB_URL = 'jdbc:mysql://127.0.0.1:3306/user_module_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:USER_TEST_DB_USERNAME = 'your_test_user'
# 在本地环境中提供 USER_TEST_DB_PASSWORD。
mvn -Pmysql-it verify
```

此命令会先运行普通测试，再运行 `UserMySqlIT`。缺少测试 URL/账户或库名不以 `_test` 结尾时失败。测试执行正式建表脚本，仅清理本次创建的记录；不要将它指向业务数据库。

| 专用验收变量 | 要求 |
| --- | --- |
| `USER_TEST_DB_URL` | 无默认值，MySQL 测试库名必须以 `_test` 结尾，并配置 UTC 参数 |
| `USER_TEST_DB_USERNAME` | 无默认值，需具备测试库建表与 CRUD 权限 |
| `USER_TEST_DB_PASSWORD` | 通过本地环境提供，默认空 |

H2 通过不能代替 MySQL 验收。当前实现已在临时 MySQL 8.0.31 实例验证正式 schema、完整 HTTP CRUD、UTC 微秒时间、摘要、逻辑删除和状态保留；具体命令与结果见实现计划。

## 历史数据与后续扩展

本次不搬运旧数据库数据。后续导入时保留用户 ID、现有 BCrypt 摘要、状态及删除标记，不重新编码摘要，不自动清理资源归属。

导入前核实历史 `DATETIME` 的存储时区：旧工程 JDBC 默认配置为 `Asia/Shanghai`，当前将这些列解释为 UTC。不能仅凭旧 JDBC 配置断定历史值的真实时区；若存的是当地时间，应在专门的数据迁移步骤按已确认的原时区转换。

## 启用 OpenAI

用户模块不需要此步骤。后续模型用例需要时设置 `SPRING_AI_MODEL_CHAT=openai`、`OPENAI_API_KEY`，可通过 `OPENAI_MODEL` 覆盖默认模型。真实凭据只放在本地环境，不提交 `.env` 或带凭据的配置。
