# 基础用户模块迁移设计

日期：2026-10-07。
状态：用户已确认并要求实施；实现和交付验证已完成。执行证据见[实现计划与执行记录](../plans/2026-10-07-user-module.md)。

## 目标与依据

在 `ai-item-summery-rebuild` 中构建用户资料管理、BCrypt 密码摘要存储、跨模块用户引用查询和模拟身份机制。外部行为以用户提供的迁移说明为准。

已读取新工程的 AGENTS.md、README、pom.xml、application.yml、全部源码和测试。已核对旧工程的用户 Controller、请求对象、服务、实体、Mapper、密码编码器、模拟身份、用户引用、公共错误处理、请求标识及相关测试。

设计开始时，新工程只有启动类和健康检查，技术基线为 Java 21、Spring Boot 4.1.1、Spring AI 2.0.1。现已按本设计加入用户模块。当前目录没有 Git 元数据，无法核对提交历史或提交本文。

## 方案选择

| 方案 | 做法 | 收益与代价 |
| --- | --- | --- |
| 推荐：MySQL + MyBatis-Plus | 使用 Boot 4 专用 starter，持久化适配实现应用层仓储接口 | 最接近旧模块的 UUID 和逻辑删除机制；需要数据库配置，并增加持久化对象到领域对象的转换 |
| MySQL + Spring JDBC | 手写查询及条件更新，由仓储实现生成 UUID | SQL 行为更直接；需要重新实现旧 Mapper 提供的映射和逻辑删除过滤，迁移差异更多 |

已选择第一种方案。MyBatis-Plus 官方安装文档列出了 `com.baomidou:mybatis-plus-spring-boot4-starter:3.5.17`；目标工程已完成实际依赖解析、构建和真实 MySQL 验证。

不引入登录、退出、JWT、角色权限、账户启停、分页、邮箱唯一约束、关联资源清理或其他业务模块。

## 分层与组件

根包继续使用 `com.item.aisummary`，保持单个 Maven 工程。

| 位置 | 职责 |
| --- | --- |
| `web.user` | `UserController`、创建和修改请求、固定六字段响应；执行 HTTP 字段校验及请求/响应映射 |
| `web.error` | 公共 `ProblemDetail` 异常映射；只迁移本范围涉及的错误 |
| `application.user` | 创建、查询、修改、删除用例；应用命令、资料结果和用户引用接口 |
| `application.user.port` | `UserRepository` 和 `PasswordHasher`；应用服务通过接口访问持久化及密码编码 |
| `domain.user` | 用户数据、文本整理规则和用户不存在异常；不含 Spring Web、Spring AI 或 MyBatis 注解 |
| `infrastructure.user.persistence` | 带 MyBatis-Plus 注解的持久化对象、Mapper、仓储适配；实现 UUID、查询过滤和条件逻辑删除 |
| `infrastructure.user.reference` | 按模拟身份配置切换固定用户引用与数据库用户引用实现 |
| `infrastructure.security` | 使用 `BCryptPasswordEncoder` 实现密码摘要能力，不接入 Spring Security Web 过滤器链 |
| `infrastructure.auth` | 模拟身份配置、MVC 拦截器和请求属性读写；HTTP 层读取身份后再向应用层传递必要数据 |
| `infrastructure.web` | 请求标识过滤器；响应头和请求属性提供 `X-Request-Id`，不存储身份 |

Controller 不调用 Mapper 或密码编码器。应用服务不依赖 HTTP 请求/响应对象或具体数据库实现。领域对象与持久化对象分开，避免旧工程中的反向依赖。

## HTTP 合同

保持 `/api/users`，覆盖本项目“新增业务接口优先使用 /api/v1”的一般建议。

| 请求 | 成功响应 |
| --- | --- |
| `POST /api/users` | `201`，用户对象 |
| `GET /api/users` | `200`，用户数组 |
| `GET /api/users/{id}` | `200`，用户对象 |
| `PUT /api/users/{id}` | `200`，更新后用户对象 |
| `DELETE /api/users/{id}` | `204`，无响应体 |

成功响应直接返回对象或数组。用户对象仅包含 `id`、`name`、`email`、`status`、`createAt`、`updateAt`。时间使用 `Instant`，JSON 输出明确时区的 ISO-8601 时间。密码、摘要和删除时间不得进入响应。

列表无分页、搜索条件或排序承诺。详情、修改和删除将不存在及已删除用户统一视为不存在，返回 `404`。

## 输入与密码处理

创建和修改都要求 `name` 非空白且最多 100 个字符，`email` 非空白、格式合法且最多 254 个字符。HTTP 层先执行 Bean Validation，应用服务随后使用与旧实现一致的 `trim()` 整理名称、邮箱和 ID。邮箱不转为小写，不增加重复检查。

创建必须提交非空白密码。修改必须同时提交名称和邮箱；省略密码或提交 `null` 保留旧摘要，空字符串及全空白返回 `400`。密码原样编码，不去除首尾空格。请求中的密码继续声明最多 255 个字符。

用户确认了 BCrypt 的新密码边界：UTF-8 编码超过 72 字节时，返回 `400 / REQUEST_VALIDATION_ERROR`，错误只说明长度限制，不回显内容。不截断密码，也不增加预哈希步骤。该规则是本次补充，不能描述成旧模块已实现的行为；已经使用 Boot 4.1.1 实际解析的 Spring Security Crypto 7.1.1 验证 72/73 字节、多字节字符、首尾空格、随机盐及历史摘要匹配。

直接使用 BCrypt 编码器，保持无 `{bcrypt}` 前缀的摘要格式。历史摘要不重新编码，历史用户 ID、状态、删除标记和时间不重写。本任务交付兼容表结构和代码，不自动连接或搬运旧数据库的数据。

## 持久化与并发语义

提供 `deploy/mysql/schema.sql`，只包含本次需要的 `user` 表；字段、长度、非空约束、`DATETIME(6)`、默认状态及主键与用户说明一致。名称和邮箱无唯一约束。初始化脚本由开发者对选定数据库手动执行，应用启动不自动改写已有表或历史数据。

创建使用 MyBatis-Plus 的 32 字符 UUID，`status=1`、`deleted_at=null`，创建时间和更新时间取同一次应用时钟。应用服务注入 `Clock`，测试可固定时间。

查询由 MyBatis-Plus 过滤逻辑删除记录，并保留单条和列表的防御检查。用户引用查询不依据 `status` 过滤。

修改先查询，再更新名称、邮箱、可选摘要和更新时间。ID、创建时间及状态保持原值。

删除先查询，再执行 `id = ? AND deleted_at IS NULL` 的更新；`deleted_at` 使用数据库 `CURRENT_TIMESTAMP(6)`，`update_at` 使用应用时间。其他资料和关联资源保持原值。

创建、修改、删除的影响行数必须等于 1，否则返回脱敏 `500 / INTERNAL_ERROR`。维持旧模块的并发语义，不增加版本号或乐观锁；并发删除的失败请求仍可能因零行更新返回 `500`。顺序重复删除返回 `404`。

数据库连接和会话采用 UTC，使用显式 UTC 类型处理器映射 `Instant` 与 `DATETIME(6)`；已在 H2 和真实 MySQL 验证往返及微秒精度。历史数据导入前必须核实原 DATETIME 存储时区：旧 JDBC 默认 Asia/Shanghai 不能证明历史物理值的实际时区，本任务不自动重写旧数据。

## 模拟身份与用户引用

| 环境变量 | 默认值 |
| --- | --- |
| `APP_AUTH_MOCK_ENABLED` | `true` |
| `APP_AUTH_MOCK_USER_ID` | `mock-user` |
| `APP_AUTH_MOCK_USERNAME` | `mock-user` |

对 `/api/**` 的非 `OPTIONS` 请求注入固定身份，请求完成时清理。身份只放在请求属性，不使用 Session 或线程本地变量。忽略 Authorization、JWT 和 Cookie，不创建用户表记录，不因 CRUD 改变模拟身份。

提供可要求当前身份的读取入口；未取得身份时抛出可映射为 `401 / AUTHENTICATION_REQUIRED` 的异常。当前没有文件、知识库接口，因此通过测试专用 Controller 验证该行为，不新增无需求的生产接口。

用户管理接口保持现有开放行为，不读取当前身份或增加权限校验。关闭模拟身份后用户 CRUD 仍可调用。

跨模块接口保留 `UserReferenceService.requireExisting(userId)`，仅返回 ID 和名称。模拟模式只接受配置中的 ID，完全不访问仓储，其他 ID 返回参数异常。关闭模拟模式后查询未删除的数据库用户，不存在或已删除返回用户不存在异常，不检查 `status`。

## 公共错误

错误响应为 `application/problem+json`，包含 `type`、`title`、`status`、`detail`、`instance`、`code`。请求标识过滤器提供 `requestId` 和响应头。字段校验错误附加 `errors`，每项只有 `field`、`message`。

| 情况 | HTTP 状态 | code |
| --- | --- | --- |
| 字段校验或业务参数错误 | 400 | `REQUEST_VALIDATION_ERROR` |
| JSON 无法解析 | 400 | `REQUEST_BODY_INVALID` |
| 用户不存在或已删除 | 404 | `RESOURCE_NOT_FOUND` |
| 缺少当前身份 | 401 | `AUTHENTICATION_REQUIRED` |
| 写入影响行数异常或未处理错误 | 500 | `INTERNAL_ERROR` |

未知错误的对外 detail 固定为“服务器内部错误”。日志和错误响应不输出密码、摘要、请求体或完整用户资料。请求标识采用格式受限的客户端值或生成值，不承担认证职责。ProblemDetail 显式采用 `urn:problem:<错误码的小写连字符形式>`，避免 Boot 4 省略默认 type 字段。

## 依赖、配置和启动

保持 Java 21、Spring Boot 4.1.1、Spring AI 2.0.1。新增 Validation starter、MyBatis-Plus 3.5.17 Boot 4 starter、MySQL Connector/J、Spring Security Crypto，以及 Boot 4 MVC 测试支持。除 MyBatis-Plus 外，使用 Boot 的依赖管理版本，不引入完整 Security starter。

新增数据库环境变量 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，只在配置中引用，不读取旧工程的本地凭据文件。

运行应用时配置 MySQL，并手动执行正式 schema。继续保持 `spring.ai.model.chat=none`，不需要模型 API Key。数据库初始化、环境变量、HTTP 示例和学习目标已同步记录到 README 和 AGENTS.md。

显式 MySQL 验收使用 `mysql-it` Maven profile 和 `USER_TEST_DB_URL`、`USER_TEST_DB_USERNAME`、`USER_TEST_DB_PASSWORD`。URL 无默认值且数据库名称必须以 `_test` 结尾，禁止让普通测试隐式使用外部数据库。

H2 仅用于测试，以 MySQL 兼容模式运行。上下文和 HTTP 集成测试主动使用独立测试数据库，不依赖本机 MySQL、Docker、模型 API 或个人凭据。H2 的通过不能当成 MySQL 验收通过。

## 修改范围

- `pom.xml`：仅增加上述本任务依赖与适当的测试配置。
- `src/main/resources/application.yml`：数据库和模拟身份的环境变量绑定，保留模型关闭默认值。
- `deploy/mysql/schema.sql`：兼容用户表结构。
- `src/main/java/com/item/aisummary/` 下上表列出的实际职责组件。
- 对应测试包及 `src/test/resources/`：测试数据库配置和测试专用建表脚本。
- `README.md`、`AGENTS.md`：同步阶段范围、包结构、环境变量、启动及验证方式。
- 本设计文档与后续实现计划：记录决策、执行状态和验证证据。

原健康检查路径和响应继续保留，不修改旧工程。

## 验证方式与当前限制

先编写行为测试，再实现。应用服务测试使用假的仓储或 Mock 和固定时钟；密码测试使用真实 BCrypt；HTTP 测试覆盖完整状态码、字段和错误结构；持久化测试验证查询过滤、摘要保存及逻辑删除；配置测试验证身份模式切换和只装配一个引用实现。

重点覆盖：重复邮箱可创建、邮箱大小写保留、带空格邮箱的实际 HTTP 校验、密码首尾空格、null/省略/空白密码、随机盐、密码字节边界、逻辑删除、非 1 状态用户引用、影响行数异常、OPTIONS 和请求属性清理，以及关闭模拟身份后 CRUD 仍开放。

完成实现后执行 `mvn clean verify`，检查依赖解析和构建产物，实际启动并请求 `GET /api/health`。真实 MySQL 验收应对专用测试数据库执行建表和完整 HTTP CRUD，再检查物理行仍在、删除时间/更新时间、状态不变及时间精度。

实际验证使用 Maven 3.9.11、JDK 21.0.7。最终 `mvn clean verify` 的 45 项普通测试全部通过，产物和依赖树检查通过。已用任务专用临时 MySQL 8.0.31 实例完成 1 项真实 MySQL 集成测试，并实际启动打包应用验证健康检查和 HTTP CRUD。临时进程和数据目录已清理；原 MySQL80 服务仍停止。没有连接或搬运旧数据库数据。

## 流程待办

- [x] 分类为新增子系统，采用架构级设计流程。
- [x] 核对新旧工程源码、配置、文档和相关测试。
- [x] 评估可视化需要：本设计可由文字和表格说明，不启用视觉伴侣。
- [x] 整理推荐方案及 JDBC 替代方案。
- [x] 明确拟补充的密码边界和数据库启动前置条件，交由本次设计确认。
- [x] 形成可审查的设计草案并检查范围及内部一致性。
- [x] 用户确认完整设计。
- [x] 使用 writing-plans 编写实现计划。
- [x] 编写测试并实现用户模块。
- [x] 执行构建、HTTP 和数据库验证并记录实际结果。

Git 提交步骤当前不具备执行条件：目录没有 Git 仓库。实现状态和验证范围以执行记录为准。

## 官方参考

- MyBatis-Plus 安装与 Boot 4 坐标：https://baomidou.com/getting-started/install/
- Spring Security 密码存储与 BCrypt：https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html
- Spring Boot 4.1.1 的本机缓存 BOM：`org.springframework.boot:spring-boot-dependencies:4.1.1`；最终已解析 Crypto 7.1.1、Connector/J 9.7.0，详见执行记录。
- Connector/J 日期处理与会话时区：https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-datetime-types-processing.html
