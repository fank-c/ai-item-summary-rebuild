# AGENTS.md

## 适用范围

本文件适用于整个项目目录。编码代理在读取和修改项目文件前，必须先遵守本文件；用户当前指令优先于本文件。

## 项目定位

本项目主要用于学习和复现 AI 应用中的常见功能，以 Spring Boot + Spring AI 为基础，逐步形成可运行、可测试、可说明的示例。

后续功能会按独立的小主题逐步加入，例如：

- 对话：理解模型调用、消息上下文和基础会话流程。
- RAG（检索增强生成）：理解文档加载、切分、向量检索和生成回答的完整链路。
- 其他 AI 能力：根据实际学习目标逐步扩展，不预先引入无关组件。

每个功能都应说明学习目标、关键 Spring AI API、运行方式、输入输出和测试方式。优先保持实现简单、边界清晰，只有在功能确实需要时才引入数据库、向量数据库、鉴权或生产级治理能力。

当前阶段维护基础工程和用户模块：用户资料 CRUD、BCrypt 密码摘要、跨模块用户存在性查询、模拟请求身份及公共错误处理。用户模块已按明确需求接入 MySQL；真实登录、退出、角色权限、账户启停及其他业务模块不在本阶段范围。新增其他基础设施或业务前，必须有明确的用户需求。

## 技术基线

- Java：21。
- Spring Boot：4.1.1。
- Spring AI：2.0.1。
- 构建工具：Maven 3.9+。
- 用户持久化：MySQL、MyBatis-Plus 3.5.17，使用 `mybatis-plus-spring-boot4-starter`。
- 密码摘要：Spring Security Crypto 的 BCrypt；不接入完整 Security starter。
- H2 仅用于自动测试；驱动、Crypto、Validation、H2 和测试库版本由 Spring Boot 管理。
- 包名根路径：`com.item.aisummary`。
- 依赖版本统一由 `pom.xml` 管理，Spring AI 依赖使用 `spring-ai-bom`。

除非用户明确要求，不要自行升级 Spring Boot、Spring AI 或 Java 版本。升级时必须检查兼容性，并同步更新 `pom.xml`、`README.md` 和本文件。

## 修改前检查

1. 先读取相关源码、配置、测试和 README，确认现有行为。
2. 使用项目已有结构和命名，不要为了小改动进行无关重构。
3. 修改依赖或配置前，确认对应版本的官方文档和 Maven 坐标。
4. 发现需求涉及多个独立模块时，先拆分范围，再逐步实现。

## 目录与职责

当前源码结构：

```text
src/main/java/com/item/aisummary/
├── AiItemSummaryApplication.java
├── web/
│   ├── HealthController.java
│   ├── user/          # CRUD、请求校验、六字段响应
│   └── error/         # ProblemDetail 公共异常映射
├── application/
│   ├── user/          # 用户用例、port 和 reference 合同
│   └── auth/          # 最小请求身份及缺少身份异常
├── domain/
│   └── user/          # 用户业务数据与文本规则，无框架依赖
└── infrastructure/
    ├── user/          # 服务装配、MyBatis 持久化、引用切换
    ├── security/      # BCrypt 摘要适配
    ├── auth/          # 请求属性上下文、模拟身份配置和拦截器
    └── web/           # 请求标识过滤器

src/main/resources/application.yml
src/test/java/com/item/aisummary/     # 与生产包结构对应的行为测试
src/test/resources/                 # 隔离 H2 配置及测试表结构
deploy/mysql/schema.sql             # 正式用户表结构
```

### 工程组织原则

- 当前采用单个 Maven 工程，通过 Java 包划分模块职责。
- 保留 `web / application / domain / infrastructure` 四层结构，层内按业务分包，例如 `web.summary`、`application.summary` 和 `domain.summary`；对话、RAG 等功能沿用相同方式。
- 按实际业务需求逐步创建包和类，不提前创建空目录，也不为了凑齐层次创建无职责的类。
- 当前不拆分多个 Maven 模块。后续确有独立复用、发布或依赖隔离需求时，再评估拆分。

### 四层职责

| 层 | 职责 | 边界 |
| --- | --- | --- |
| `web` | Controller、请求参数校验、HTTP 协议转换、响应映射和统一异常映射 | 不承载业务规则，不直接调用模型、数据库客户端或第三方 HTTP SDK |
| `application` | 应用服务和用例编排，协调领域规则与外部能力 | 通过本层定义的接口调用外部能力，不依赖基础设施实现类 |
| `domain` | 各功能的领域概念、值对象与业务规则 | 不依赖 Spring Web、Spring AI、数据库客户端或模型供应商 SDK |
| `infrastructure` | AI 模型调用、Prompt 加载，以及按需增加的持久化或外部 HTTP 服务适配 | 实现应用层接口，封装外部协议、供应商细节和异常转换 |

领域层保持轻量。只有出现实际业务规则时，才引入领域服务、实体或其他抽象；有存储需求时，再定义相应的仓储接口。

### 依赖方向

```text
web ───────────────→ application ─────→ domain
                          ↑               ↑
                    infrastructure ───────┘
```

- `web` 调用 `application`，`application` 使用 `domain` 并定义所需的外部能力接口。
- `infrastructure` 实现应用层接口，可以使用领域类型；`domain` 不反向依赖其他层。
- Spring 启动配置将接口实现注入应用服务。具体适配实现及其装配配置放在基础设施边界内。

### 摘要用例的扩展示例

以下是后续实现摘要业务时的职责示例，不是当前已经存在的文件，也不要求一次性全部创建：

```text
src/main/java/com/item/aisummary/
├── AiItemSummaryApplication.java
├── web/
│   ├── HealthController.java
│   ├── summary/
│   │   ├── SummaryController.java
│   │   ├── CreateSummaryRequest.java
│   │   └── SummaryResponse.java
│   └── error/
│       └── GlobalExceptionHandler.java
├── application/
│   └── summary/
│       ├── GenerateSummaryService.java
│       ├── GenerateSummaryCommand.java
│       └── port/
│           └── SummaryGenerator.java
├── domain/
│   └── summary/
│       ├── Summary.java
│       └── SummaryOptions.java
└── infrastructure/
    └── ai/
        ├── SpringAiSummaryGenerator.java
        └── AiConfiguration.java

src/main/resources/
├── application.yml
└── prompts/
    └── summary.st
```

`SummaryGenerator` 表达应用所需的摘要生成能力；`SpringAiSummaryGenerator` 通过 Spring AI 实现该接口。应用服务负责组织“接收输入 → 协调领域规则 → 调用生成接口 → 返回结果”的流程，Controller 只负责请求与响应转换。

可复用或需要版本管理的 Prompt 放在 `src/main/resources/prompts/`，由基础设施层加载。模型名称与调用参数通过配置管理，应用层只传递业务所需的摘要选项。

测试包与对应生产代码的包结构保持一致。应用服务测试使用假的生成器或 Mock，不依赖真实模型。

## 编码约定

- 使用构造器注入，不使用字段注入。
- 类、方法和变量使用清晰的英文名称。
- 简单且不可变的请求、响应对象优先使用 `record`。
- API 路径使用 `/api` 前缀；新增业务接口优先使用 `/api/v1`。用户迁移接口明确保持 `/api/users`。
- 配置项放在 `application.yml` 或配置类中，不把可变配置硬编码到业务代码。
- 修改行为时优先先写测试，再写实现；测试必须验证可观察行为，不要只验证内部实现细节。
- 保持 UTF-8 编码和现有文件格式。

## Spring AI 规则

- 当前使用 `spring-ai-starter-model-openai`。
- 用例通过应用层定义的外部能力接口调用 AI，例如摘要功能使用 `SummaryGenerator`。基础设施适配依赖 Spring AI 抽象接口，供应商特有实现封装在该边界内；应用层和领域层不直接依赖 `ChatModel`、`ChatClient` 或供应商 SDK。
- 默认配置必须保持 `spring.ai.model.chat=none`，使没有 API Key 的环境可以启动和测试。后续新增依赖模型的接口时，必须明确模型关闭时的错误响应，并保持健康检查和上下文测试正常。
- 需要启用 OpenAI 时使用环境变量：

```text
SPRING_AI_MODEL_CHAT=openai
OPENAI_API_KEY=<secret>
OPENAI_MODEL=<model-name>
```

- API Key、Token、密码、私钥和生产连接串禁止写入源码、配置文件、测试代码和提交记录。
- 不得在普通单元测试或上下文测试中访问真实模型 API、外部网络或个人凭据。
- Prompt 不要直接散落在 Controller 中；可复用或需要版本控制的 Prompt 放在 `src/main/resources/prompts/`。
- 日志禁止输出密钥、完整用户隐私数据、完整 Prompt 或完整模型响应。

## 配置规则

- `src/main/resources/application.yml` 只保留安全默认值和环境变量引用。
- 新增环境变量时，必须同时更新 README 和本文件。
- 不提交 `.env`、本地 IDE 配置和 `target/` 生成物。
- 修改端口、模型开关或启动方式后，必须同步更新 README。

## 用户模块约定

- 用户服务只依赖应用层 `UserRepository` 和 `PasswordHasher`；领域对象不含 MyBatis 注解，持久化实体只留在基础设施层。
- 响应固定为 `id/name/email/status/createAt/updateAt`，不含密码、摘要或删除时间，不加成功包装。
- HTTP 先校验名称、邮箱和声明的密码长度，服务随后裁剪普通文本。密码原样编码；创建必填，修改省略或 null 保留，空白拒绝。
- HTTP 密码声明上限为 255 字符，新密码另限制为 72 个 UTF-8 字节，超限返回 400，不截断或预哈希。
- 未删除状态由 `deleted_at IS NULL` 表示，独立于 status；查询和引用不按 status 过滤。删除使用数据库 `CURRENT_TIMESTAMP(6)`，不修改账户状态或关联资源。
- 写入要求一行；维持先查询后写入的语义，不声称已有完整并发冲突治理。
- 模拟身份只放在请求属性中，跳过 OPTIONS，只适用于 `/api/**`，结束后清理，不创建 Session、不解析凭据。
- 用户引用模拟模式只接受配置用户且不查数据库；关闭模拟后查询未删除用户。
- 用户 CRUD 当前不读取身份或执行权限判断，关闭模拟身份不会自动保护该接口。
- 错误统一为 ProblemDetail，显式包含 type、title、status、detail、instance、code；请求标识可取得时带 requestId。字段 errors 只含 field/message；未知异常固定显示“服务器内部错误”。
- 生产 schema 位于 `deploy/mysql/schema.sql`，手动执行，不自动初始化或修改旧数据。数据库连接与会话采用 UTC，DATETIME(6) 使用显式 UTC 类型处理器。
- 历史数据导入属于单独步骤：保留 ID、摘要和删除状态，先确认历史 DATETIME 时区，不重新编码摘要。

### 用户模块环境变量

| 变量 | 默认值与规则 |
| --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/ai_item_summary?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`；覆盖时保留 UTC 参数 |
| `DB_USERNAME` | 空，运行前提供数据库账户 |
| `DB_PASSWORD` | 空，本地环境提供，不写入仓库 |
| `APP_AUTH_MOCK_ENABLED` | `true` |
| `APP_AUTH_MOCK_USER_ID` | `mock-user` |
| `APP_AUTH_MOCK_USERNAME` | `mock-user` |
| `SERVER_PORT` | `8080` |
| `USER_TEST_DB_URL` | 仅显式 MySQL 验收使用，无默认值；数据库名称必须以 `_test` 结尾并配置 UTC 参数 |
| `USER_TEST_DB_USERNAME` | 仅显式 MySQL 验收使用，无默认值，需测试库建表与 CRUD 权限 |
| `USER_TEST_DB_PASSWORD` | 仅显式 MySQL 验收使用，默认空，本地环境提供 |

运行应用前配置 MySQL 并手动建表，再执行 `mvn spring-boot:run`；模型仍默认关闭。普通测试使用独立 H2，不要求运行时数据库或模型凭据。

## 测试与验证

代码变更后至少运行：

```bash
mvn clean verify
```

完成前还应根据改动类型补充验证：

- 修改 Controller：验证路径、HTTP 状态码和响应结构。
- 修改 Spring 配置：运行上下文测试，并确认无 API Key 时仍能启动。
- 修改 AI 配置：确认模型开关、API Key 和模型名称均可通过环境变量覆盖。
- 修改依赖：检查 Maven 依赖解析和最终构建产物。
- 修改启动行为：实际启动应用并请求 `GET /api/health`。

不能访问外部服务或缺少必要凭据时，说明验证边界，不要伪造成功结果。

用户行为测试覆盖 HTTP、摘要、服务失败、真实 Mapper SQL、引用切换和请求身份。普通测试强制关闭模型并使用 H2，不依赖本机 MySQL、Docker 或个人凭据。

显式真实 MySQL 验收：先配置上表中的 `USER_TEST_DB_*`，再运行 `mvn -Pmysql-it verify`。该 profile 执行正式 schema 和完整 HTTP CRUD，仅清理本次创建的测试记录。H2 验证不能被描述为 MySQL 验证。实际启动验证仍应请求 `GET /api/health`。

## 文档同步

以下变化必须同步修改 README 和本文件：

- 技术栈或依赖版本变化。
- 模块结构、职责或依赖方向变化。
- 启动命令变化。
- 环境变量变化。
- API 路径或响应变化。
- 测试和构建命令变化。

公开 HTTP 接口文档维护在 `docs/api.md`，README 提供入口。新增或修改请求参数、校验规则、状态码、响应字段或错误码时，同步更新 API 文档；只记录生产源码中实际存在的接口，不把测试 Controller 或内部 Java 合同列为公开接口。

## 代理交付要求

完成任务时，最终说明：

1. 修改了哪些文件，以及每个文件的目的。
2. 执行了哪些验证命令，以及实际结果。
3. 未完成的验证、已知限制或需要用户决策的事项。

不要声称未实际运行的测试、构建或接口验证已经通过。
