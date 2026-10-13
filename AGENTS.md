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

当前阶段维护基础工程、用户、核心文件、知识库基础服务、TXT/Markdown 生产文档解析器、TokenBudgetKnowledgeChunker（FLAT 与 PARENT_CHILD 模式）和固定 RAG 构建骨架：用户资料 CRUD、BCrypt 密码摘要、跨模块用户存在性查询、模拟请求身份、公共错误处理，文件上传、下载签名、删除、内部内容读取，以及知识库四表、片段父子字段、策略 ports、源字节读取、组式保存、向量适配、构建生命周期与版本发布。当前已实现 TXT 和 Markdown 两种生产文档解析器以及 TokenBudgetKnowledgeChunker（FLAT 与 PARENT_CHILD 两模式）；RAG 默认关闭，显式开启但缺 OpenAI Embedding、Qdrant 等外部依赖或配套 Bean 时拒绝启动。解析器与分块器行为由单元测试真实调用覆盖（不调用网络），构建编排与本地发布行为由 KnowledgeChunkingBuildIntegrationTest（真实本地 parser/chunker、H2 Mapper/发布与文件/向量替身）等测试覆盖，真实 MySQL、OpenAI/Qdrant 外部调用及具体 PDF/DOCX 解析尚未验收。元数据使用 MySQL；文件存储可选 MinIO 或阿里云 OSS。真实登录、权限、账户启停、客户端直传、可用性框架、知识库 HTTP、具体 PDF/DOCX/OCR 解析、异步任务、检索和问答不在本阶段范围。新增其他基础设施或业务前，必须有明确的用户需求。

## 技术基线

- Java：21。
- Spring Boot：4.1.1。
- Spring AI：2.0.1。
- 构建工具：Maven 3.9+。
- 用户、文件及知识库元数据持久化：MySQL、MyBatis-Plus 3.5.17，使用 `mybatis-plus-spring-boot4-starter`。
- 对象存储：MinIO Java SDK 8.5.17、阿里云 OSS SDK 3.18.2，按配置启用原始适配器。
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
│   ├── health/        # 健康检查
│   ├── user/          # controller、request、response
│   ├── file/          # controller、response、upload
│   └── error/         # ProblemDetail 公共异常映射
├── application/
│   ├── user/          # service、command、result、port、reference
│   ├── file/          # service、content、policy、result、port
│   ├── knowledge/
│   │   ├── service/   # KnowledgeBase/Document/Revision/Chunk 四个基础服务
│   │   ├── command/   # SaveKnowledgeChunkCommand
│   │   ├── port/      # 四个基础仓储接口
│   │   └── indexing/  # build、source、parse、chunk、vector 专用能力组
│   └── auth/          # 最小请求身份、身份 ID 规则及缺少身份异常
├── domain/
│   ├── user/          # model、exception
│   ├── file/          # model、rule、exception
│   └── knowledge/     # model、value（含 SourceLocation）、enums、rule、exception
└── infrastructure/
    ├── user/          # config、persistence、reference
    ├── file/          # config、persistence、storage
    ├── knowledge/
    │   ├── config/    # 基础 KnowledgeConfiguration
    │   ├── persistence/ # 四表 MyBatis 持久化及类型处理
    │   └── indexing/  # config（RAG 条件配置、KnowledgeParserConfiguration、KnowledgeChunkerConfiguration、Properties）、parse（TXT/MD 解析器及 Utf8TextDecoder）、chunk（TokenBudgetKnowledgeChunker、JTokkitTokenBudget、SourceMappedText、TokenRangeSplitter）、vector（Spring AI 适配）
    ├── persistence/   # 共享 UTC typehandler
    ├── time/          # 共享 UTC Clock 装配
    ├── security/      # BCrypt 摘要适配
    ├── auth/          # config、web 请求身份集成
    └── web/           # 请求标识过滤器

src/main/resources/application.yml
src/test/java/com/item/aisummary/     # 与生产包结构对应的行为测试
src/test/resources/                 # 隔离 H2 配置及测试表结构
deploy/mysql/schema.sql             # 正式用户、文件及知识库四张核心表结构
```

### 工程组织原则

- 当前采用单个 Maven 工程，通过 Java 包划分模块职责。
- 保留 `web / application / domain / infrastructure` 四层结构，按“层 → 业务 → 职责”组织，例如 `web.user.controller`、`application.knowledge.service` 和 `domain.knowledge.model`；RAG 专用流程在 `application.knowledge.indexing` 下按能力聚合，不强行套用基础服务的分层目录。
- Web 按 `controller / request / response` 分类；基础应用服务按 `service / command / result / port` 分类，跨模块用户引用与文件内容读取分别集中在 `reference / content`。领域按实际职责使用 `model / value / enums / rule / exception`，不把不同层的数据对象统一塞入 DTO 包。
- 基础设施装配与配置绑定位于各业务 `config`；RAG 专用配置与 Properties 放在 `infrastructure.knowledge.indexing.config`，向量适配放在 `infrastructure.knowledge.indexing.vector`。MyBatis 按 `persistence.entity / mapper / repository / typehandler` 分包，厂商存储适配留在 `storage`。共享 UTC 类型处理器位于 `infrastructure.persistence.typehandler`，共享时钟位于 `infrastructure.time.ClockConfiguration`，业务模块通过注入使用时钟。
- `application.auth`、`web.error`、用户引用和已有 SDK 适配等单一职责包不强制继续细分。跨职责引用使用显式导入，测试沿用对应生产包，集成测试按主要验证职责归类。
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
│   ├── health/
│   │   └── HealthController.java
│   ├── summary/
│   │   ├── controller/
│   │   │   └── SummaryController.java
│   │   ├── request/
│   │   │   └── CreateSummaryRequest.java
│   │   └── response/
│   │       └── SummaryResponse.java
│   └── error/
│       └── GlobalExceptionHandler.java
├── application/
│   └── summary/
│       ├── service/
│       │   └── GenerateSummaryService.java
│       ├── command/
│       │   └── GenerateSummaryCommand.java
│       └── port/
│           └── SummaryGenerator.java
├── domain/
│   └── summary/
│       ├── model/
│       │   └── Summary.java
│       └── value/
│           └── SummaryOptions.java
└── infrastructure/
    └── summary/
        ├── ai/
        │   └── SpringAiSummaryGenerator.java
        └── config/
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
- API 路径使用 `/api` 前缀；新增业务接口优先使用 `/api/v1`。用户与文件迁移接口明确保持 `/api/users`、`/api/files`。
- 配置项放在 `application.yml` 或配置类中，不把可变配置硬编码到业务代码。
- 修改行为时优先先写测试，再写实现；测试必须验证可观察行为，不要只验证内部实现细节。
- 保持 UTF-8 编码和现有文件格式。

## Spring AI 规则

- 当前使用 `spring-ai-starter-model-openai`、`spring-ai-markdown-document-reader` 和 `spring-ai-starter-vector-store-qdrant`，版本均由现有 2.0.1 BOM 管理。直接声明 `org.commonmark:commonmark` 和 `org.commonmark:commonmark-ext-gfm-tables:0.22.0`（与 Spring AI 2.0.1 传递 core 对齐）；保留已有 `spring-ai-markdown-document-reader` 依赖但不用其 reader（因需保留原文及源位置，reader 提取和拼接文本不保留完整原语法与精确位置）；直接声明 `com.knuddels:jtokkit:1.1.0`（原 Spring AI 2.0.1 传递依赖版本，但 Spring AI BOM 不直接管理，显式固定用于本地 CL100K_BASE 预算分词）。
- 用例通过应用层定义的外部能力接口调用 AI，例如摘要功能使用 `SummaryGenerator`。基础设施适配依赖 Spring AI 抽象接口，供应商特有实现封装在该边界内；应用层和领域层不直接依赖 `ChatModel`、`ChatClient` 或供应商 SDK。
- 默认保持 `spring.ai.model.chat=none`、`spring.ai.model.embedding=none`、`spring.ai.vectorstore.type=none` 和 RAG 构建关闭，使没有 API Key 或 Qdrant 的环境可以启动和测试。构建开启时要求注册 parser 与 FLAT chunker，并显式选择 OpenAI Embedding 与 Qdrant，缺少外部后端依赖或配套 Bean 时仍拒绝启动；关闭时不装配 `KnowledgeIndexBuildService`，基础服务和健康检查仍可用。当前已提供 TXT/Markdown 生产 parser 与 TokenBudgetKnowledgeChunker（FLAT 与 PARENT_CHILD 模式），但不代表真实外部服务已验收或生产端到端完成。
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

- `src/main/resources/application.yml` 只保留安全默认值、环境变量引用及可选的本地配置导入。
- 主配置使用 `spring.config.import=optional:file:./.env[.properties][encoding=utf-8]`，从进程工作目录读取 `.env`；缺少文件不报错。Maven、IDE 和 JAR 启动均应明确工作目录，通常使用项目根目录。
- `.env` 使用 UTF-8 Java Properties 格式：`KEY=value`，不写 `export`、不包引号，注释单独占一行；反斜杠用 `\\` 转义，Spring 仍解析 `${...}`。不把它当作 Shell 脚本执行。
- 在上述配置方式下，应用命令行参数优先于进程环境变量，进程环境变量优先于 `.env`，未定义的变量使用主配置默认值。导入不修改操作系统环境或 `System.getenv()`。
- 新增环境变量或修改默认值时，必须同时更新 `.env.example`、README 和本文件；模板覆盖所有运行变量，仅保留安全默认值和空凭据。`USER_TEST_DB_*` 在模板中只作为注释参考，验收仍通过进程环境变量配置。
- 创建本地 `.env` 前检查是否存在；同步时保留已有值，只补缺少的键，不输出本地凭据。不提交 `.env`、其他 `.env.*`、本地 IDE 配置和 `target/` 生成物；`.gitignore` 只为 `.env.example` 设置例外。
- 普通测试的 `src/test/resources/application.yml` 不导入 `.env`，继续使用 H2 并关闭模型及外部存储；配置加载测试只能使用临时目录、隔离子进程和虚构值。
- 修改端口、模型开关或启动方式后，必须同步更新 README。

## 用户模块约定

- 用户服务只依赖应用层 `UserRepository` 和 `PasswordHasher`；领域对象不含 MyBatis 注解，持久化实体只留在基础设施层。
- 响应固定为 `id/name/email/status/createAt/updateAt`，不含密码、摘要或删除时间，不加成功包装。
- HTTP 先校验名称、邮箱和声明的密码长度，服务随后裁剪普通文本。密码原样编码；创建必填，修改省略或 null 保留，空白拒绝。
- HTTP 密码声明上限为 255 字符，新密码另限制为 72 个 UTF-8 字节，超限返回 400，不截断或预哈希。
- `UserRepository.update(user,passwordChanged)` 显式表达密码更新意图。标志为 false 时 SQL 不包含 password 列，保留数据库当前摘要；为 true 时写入应用提供的新摘要。仅更新 name/email/update_at 及可选 password，不写 id/status/create_at/deleted_at；WHERE 保留 ID 和未删除条件。响应仍取已读取快照，不声称解决两个显式密码更新或资料更新之间的覆盖。
- 未删除状态由 `deleted_at IS NULL` 表示，独立于 status；查询和引用不按 status 过滤。删除使用数据库 `CURRENT_TIMESTAMP(6)`，不修改账户状态或关联资源。
- 写入要求一行；维持先查询后写入的语义，不声称已有完整并发冲突治理。
- 模拟身份只放在请求属性中，跳过 OPTIONS，只适用于 `/api/**`，结束后清理，不创建 Session、不解析凭据。
- 模拟用户 ID 沿用 trim()，仅启用模拟时通过无 Spring 依赖的 `ActorUserIdRules` 校验非空及最多 64 个 Unicode 码点；补充平面字符计 1。关闭时不校验未使用的 ID 长度。
- 用户引用模拟模式只接受配置用户且不查数据库；关闭模拟后查询未删除用户。
- 用户 CRUD 当前不读取身份或执行权限判断，关闭模拟身份不会自动保护该接口。
- 错误统一为 ProblemDetail，显式包含 type、title、status、detail、instance、code；请求标识可取得时带 requestId。字段 errors 只含 field/message；未知异常固定显示“服务器内部错误”。
- 不支持的 Content-Type 专门映射为 415 UNSUPPORTED_MEDIA_TYPE，type 为 urn:problem:unsupported-media-type，title 为“请求媒体类型不支持”，detail 为“请求的 Content-Type 不受支持”。保留异常协议头和公共请求标识，不回显请求体或框架消息；不在此修复中扩展 406。
- 生产 schema 位于 `deploy/mysql/schema.sql`，手动执行，不自动初始化或修改旧数据。数据库连接与会话采用 UTC，DATETIME(6) 使用显式 UTC 类型处理器。
- 历史数据导入属于单独步骤：保留 ID、摘要和删除状态，先确认历史 DATETIME 时区，不重新编码摘要。

### 用户模块环境变量

| 变量 | 默认值与规则 |
| --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/ai_item_summary?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`；覆盖时保留 UTC 参数 |
| `DB_USERNAME` | 空，运行前提供数据库账户 |
| `DB_PASSWORD` | 空，本地环境提供，不写入仓库 |
| `APP_AUTH_MOCK_ENABLED` | `true` |
| `APP_AUTH_MOCK_USER_ID` | `mock-user`；启用时裁剪后非空且最多 64 个 Unicode 码点 |
| `APP_AUTH_MOCK_USERNAME` | `mock-user` |
| `SERVER_PORT` | `8080` |
| `USER_TEST_DB_URL` | 仅显式 MySQL 验收使用，无默认值；数据库名称必须以 `_test` 结尾并配置 UTC 参数 |
| `USER_TEST_DB_USERNAME` | 仅显式 MySQL 验收使用，无默认值，需测试库建表与 CRUD 权限 |
| `USER_TEST_DB_PASSWORD` | 仅显式 MySQL 验收使用，默认空，本地环境提供 |

运行应用前配置 MySQL 并手动建表，再执行 `mvn spring-boot:run`；模型仍默认关闭。普通测试使用独立 H2，不要求运行时数据库或模型凭据。

## 文件模块约定

- `FileService` 仅依赖应用层文件仓储、存储路由、上传源和约束；不依赖 MultipartFile、MyBatis 或 SDK。
- HTTP 固定三个接口：POST `/api/files?storageType=minio|aliyun`、GET `/api/files/{fileId}/download-url`、DELETE `/api/files/{fileId}`。没有详情或列表 GET，Location 只标识资源。
- 上传响应固定 `id/originName/mime/size/uploadStatus/createAt/updateAt`；新上传 uploadStatus=completed，不公开存储位置、摘要或所有者。
- 身份来自请求上下文或内部显式 actorUserId；所有文件服务入口先校验非空及最多 64 个 Unicode 码点，再执行查询、读流或存储操作。内部 ID 不裁剪、转大小写或截断。create_by 为所有者，不查询用户表。未删除、status=1、completed 且归属匹配才可下载、删除或读取；失败统一 404，无身份 401，超长内部 ID 抛 IllegalArgumentException，经 HTTP 传播时为 400 REQUEST_VALIDATION_ERROR。
- 先完整读取实际字节并计算 SHA-256，再重开相同内容上传。默认 20MB、txt/md/pdf/docx，不识别内容格式。Bucket 只取配置，Key 取前缀、UTC 日期、随机 UUID 和清理后的名称。
- 文件名和规范化 MIME 最多 255 字符；Key 保留 512 的 String.length() 上限，新生成的 Key 另限制 UTF-8 不超过 1023 字节。此值是两个存储的共同应用预算，在首次读流前检查，超限为 400 FILE_VALIDATION_ERROR，不截断名称。历史 Key 的下载、删除和内部读取不套用此新上传检查。扩展名忽略大小写，MIME 去参数并转小写，空 MIME 使用 application/octet-stream。
- 存储类型必须对应已启用适配器，不自动回退。默认两个厂商关闭，无存储凭据仍可启动；已启用时缺 endpoint、bucket 或凭据拒绝启动。
- 适配器直接调用 SDK，只保留上传、读取流、GET 签名、删除；不引入 ResilienceExecutor、重试策略、HEAD 核对、直传和异步任务。
- 下载期限配置为 1～604800 整数秒（默认 15m），不探测远端对象。地址是临时凭证，持有者可以使用。
- 先远端删除，再逻辑删除一行，deleted_at 使用数据库 CURRENT_TIMESTAMP(6)，刷新 update_by/update_at，保留 status。删除不清理知识库内容或向量。
- `FileContentService.requireOwnedProcessable(actorUserId,fileId)` 返回名称和 MIME；`openForProcessing(actorUserId,fileId)` 返回元数据和流，调用者通过 try-with-resources 关闭。后台任务必须传用户 ID。
- 业务错误 400 FILE_VALIDATION_ERROR，容器超限 413 FILE_SIZE_LIMIT_EXCEEDED，SDK 错误 502 OBJECT_STORAGE_UNAVAILABLE。数据库失败仍为 500 INTERNAL_ERROR，不暴露 SDK 或 SQL 细节。
- 上传成功后数据库失败可能留下孤立对象；删除成功后数据库失败可能留下未删除记录。无自动补偿、幂等性或完整并发治理。
- file_asset 沿用核心列，upload_expires_at 保留为空；不自动修改已有表、导入历史数据或创建 Bucket。UTC 处理复用现有类型处理器。

### 文件模块环境变量

| 变量 | 默认值与规则 |
| --- | --- |
| `APP_FILE_MAX_SIZE` | `20MB`，同时约束 multipart 单文件和业务实际字节 |
| `APP_FILE_REQUEST_MAX_SIZE` | `20MB`，整个 multipart 请求（含边界和元数据），需要上传恰好 20MB 文件时适当增大 |
| `FILE_STORAGE_KEY_PREFIX` | `files`，规范化后 1～150 字符；多字节前缀占用总 Key 的 1023 字节预算 |
| `FILE_STORAGE_DOWNLOAD_URL_EXPIRATION` | `15m`，整数秒，1s～7d |
| `FILE_STORAGE_CONNECT_TIMEOUT` | `5s`，SDK 连接；OSS 获取连接等待也采用该值 |
| `FILE_STORAGE_READ_TIMEOUT` | `60s`，SDK 读取超时 |
| `FILE_STORAGE_WRITE_TIMEOUT` | `60s`，MinIO HTTP 写入超时；OSS 无独立写入超时配置 |
| `MINIO_ENABLED` | `false` |
| `MINIO_ENDPOINT`、`MINIO_BUCKET` | 空，启用时必填，Bucket 事先创建 |
| `MINIO_REGION` | `us-east-1`，按实际部署区域覆盖 |
| `MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY` | 空，启用时必填，本地提供 |
| `MINIO_PATH_STYLE_ACCESS` | `true` |
| `ALIYUN_OSS_ENABLED` | `false` |
| `ALIYUN_OSS_ENDPOINT`、`ALIYUN_OSS_BUCKET` | 空，启用时必填 |
| `ALIYUN_OSS_ACCESS_KEY_ID`、`ALIYUN_OSS_ACCESS_KEY_SECRET` | 空，启用时必填，本地提供 |

SDK 超时要求正整数毫秒且不超过 Integer.MAX_VALUE 毫秒；不保证整个流处理的总时长。单文件与请求同时默认 20MB，请求会有 multipart 额外开销。

## 知识库基础服务与表结构约定

- 已实现知识库四表领域、MyBatis 持久化、内部基础服务和固定 RAG 构建骨架，包括策略 ports、原字节读取、组式片段保存、向量适配与版本发布。当前已提供 TXT/Markdown 生产解析器与 TokenBudgetKnowledgeChunker（FLAT 与 PARENT_CHILD 两模式）；RAG 默认关闭，开启但缺 OpenAI Embedding、Qdrant 等外部依赖或配套 Bean 时仍拒绝启动。字段见 `docs/reference/database/knowledge.md`，调用见 `docs/modules/knowledge.md`。没有知识库 HTTP、权限、删除、异步任务、实际 PDF/DOCX/OCR 解析、检索或聊天。
- 服务按用例组织，无 Service/Impl 套层。`KnowledgeBaseService` 创建、详情、列表和改名；名称 trim 后非空白且最多 100 个 Java 字符，允许多库同名。业务 ID 为 32 位 UUID，时钟复用现有 Clock 并截断到微秒。
- `KnowledgeDocumentService.register(actorUserId,knowledgeBaseId,fileId)` 先校验显式身份及库存在，再通过 `FileContentService.requireOwnedProcessable` 校验源文件。只查询元数据；不得读取正文、调用文件 Mapper 或将文件归属视为知识库授权。新文档 status=1、index_status=pending，活动版本和失败摘要为空。
- 重复库内文件登记报 `DOCUMENT_ALREADY_REGISTERED`，数据库唯一索引兜底。`KnowledgeRevisionService.create(documentId)` 先确认文档存在，使用 MAX + 1 创建 building 版本；竞争报 `REVISION_NUMBER_CONFLICT`，达到 int 上限报 `REVISION_NUMBER_EXHAUSTED`，不自动重试。新版本 chunk_count=0，created_at/started_at 为进入 building 的时间，摘要、失败信息和其他生命周期时间为空，不修改文档活动版本。
- `KnowledgeChunkService.saveBatch(revisionId,commands)` 是基础片段持久化入口，只接受 building 版本。输入没有 revisionId/片段 ID，所有片段由本次入口确定归属并生成新 ID。批次非空、正文非空白、序号为版本内非负全局顺序且批内唯一，父块和检索块共同占用，不表示原文位置；已存序号冲突报 `CHUNK_SEQUENCE_CONFLICT`，非 building 报 `REVISION_NOT_BUILDING`。不提供正文或关系更新接口。构建组保存由 indexing 层 `KnowledgeChunkGroupWriter` 负责；基础服务不提供 `saveGroups`，也不依赖 indexing 合同。
- chunk 增加可空 `parent_chunk_id` 与必填、无数据库默认值的 `chunk_role`（CONTEXT/RETRIEVAL），保留版本内序号唯一并增加 `(revision_id,parent_chunk_id,sequence_no)` 索引。CONTEXT 必须无父，RETRIEVAL 可独立或引用同版本的无父 CONTEXT；只支持两层上下文关系，不保存任意深度章节树，不新增物理外键或 SQL CHECK。角色不表示文件格式或 TEXT/TABLE/CODE 内容类别。
- `SaveKnowledgeChunkCommand.parentSequenceNo` 使用同版本全局序号引用本批或已存父块；服务预分配本批 ID，在版本锁内校验全部关系并转换为业务父 ID，返回仍按输入顺序。缺失父块抛 KnowledgeNotFoundException；缺失角色、负父序号、自引用、CONTEXT 有父及检索块作为父块抛 IllegalArgumentException，在任何插入前拒绝。父查询限定版本、不跨版本回退；原有命令和领域构造器明确创建无父 RETRIEVAL 普通块。父子正文分别保存，chunk_count 统计两种角色全部记录，不等于向量数量。不提供父块检索；构建骨架仅将已保存 RETRIEVAL 写向量，并保留活动版本约束。
- 批次采用追加语义。通过 Spring 注入的服务调用事务入口，使用 READ_COMMITTED 并锁住目标版本；基础设施检查实际连接隔离级别，不兼容的外层事务在写入前明确失败。插入和实际总数更新在同一事务中，写入行数必须符合预期。数据库异常和业务冲突继续传播并回滚整批，兼容外层事务回滚也共同回滚；同版本并发批次串行执行。不自行 new 事务服务，也不添加通用事务框架。
- `heading_path` 保持有序标题列表，标题层级为正、标题非空白；JSON NULL 与空数组分别还原。使用既有 Jackson 3 和专用类型处理器；MySQL 绑定 JSON 文本，H2 绑定原生 UTF-8 JSON 字节，避免把数组保存成字符串。
- `SourceLocation` 保持在 `domain.knowledge.value`；`KnowledgeRules.requireLocation` 集中校验偏移与页码约束，供值对象和片段校验复用。知识领域字段及持久化仍为现有扁平 `start_offset/end_offset/page_start/page_end`，不引入位置嵌套列或 schema 变化。
- token_count 可空，有值时非负，表示本行正文的计数，不累计子块。偏移同时空或满足 0 <= start_offset < end_offset，父子使用同一处理后全文 UTF-16 参照而非父块内偏移，无法可靠映射时为空；页码同时空或满足 1 <= page_start <= page_end。正文保留原样，不以片段长度限制全文偏移，不填充估算的 Token 数量或虚构位置。
- 详情与按关联列表查询对不存在的关联抛 `KnowledgeNotFoundException`；合法但无子记录时返回空列表。版本按版本号升序、片段按 sequence_no 升序。内部异常尚未定义公开 HTTP 错误合同。
- 知识库只保存自身信息，移除 `owner_user_id` 及其唯一索引。用户访问关系留待后续权限表管理，本次不创建权限表，也不将缺少所有者字段解释为公开访问。
- 保留库内文件唯一、文档内版本号唯一、版本内片段序号唯一。沿用逻辑关联，不新增物理外键或级联删除；文件归属与活动版本一致性仍需业务校验。
- 版本表保留整体状态、失败摘要、源文件摘要、片段数量和时间。不保存 `parser_strategy`、`chunking_strategy`、`chunk_size`、`chunk_overlap`、`quality_status`、`normalization_strategy`、`vector_route_key`，片段表同样不保存切片策略；旧实体和 Mapper 不可原样迁入。
- 不新增 `stage_status`，移除 `cleanup_status`。保留 `cleaned_at` 作为清理完成时间；为空不区分未清理、无需清理或清理失败。
- 保留偏移、页码和标题信息，但未知时为空。UTF-16 偏移相对于处理后全文，不是原文件字节或 PDF 坐标；源全文持久化、OCR 坐标与 Word 位置在对应需求实现时另行确定。
- 正式与 H2 schema 同步维护字段、可空性和索引。生产脚本仍手动执行，`IF NOT EXISTS` 不升级旧表；不自动导入历史数据或清理既有向量集合。缺少父子字段的旧表必须另行手动升级，原有平铺记录保留 ID/版本/序号，回填 parent_chunk_id=NULL、chunk_role=RETRIEVAL 后再约束非空，本次不连接或修改已有数据库。

### RAG 固定构建骨架约定

- `KnowledgeIndexBuildService.build(actorUserId,documentId)` 是内部构建入口。两参数形式默认选择 `ChunkingMode.FLAT`；第三参数可显式指定 `FLAT` 或 `PARENT_CHILD`。模式只选择已注册的 `KnowledgeChunker`。解析和切分分别由 `KnowledgeDocumentParser`、`KnowledgeChunker` 应用合同提供；当前已实现 TXT 和 Markdown 两种生产解析器以及 TokenBudgetKnowledgeChunker（FLAT 与 PARENT_CHILD 两种生产分块策略）。不得添加空操作或假装可用的默认策略。
- 默认 `APP_KNOWLEDGE_RAG_ENABLED=false`。显式开启时要求已注册至少一个解析器、`FLAT` 分块器、OpenAI Embedding 与 Qdrant 适配器；缺少外部后端依赖或配套 Bean 仍拒绝启动。`KnowledgeParserConfiguration` 与 `KnowledgeChunkerConfiguration` 在 RAG 开启时分别注册生产解析器与分块器（FLAT 与 PARENT_CHILD），与 `KnowledgeRagConfiguration` 依赖检查分离；`KnowledgeRagConfiguration` 继续在缺少外部 OpenAI Embedding 或 Qdrant 时拒绝启动。两解析器构造参数 `constructor(long maxSourceSize)` 复用现有 `KnowledgeRagProperties.maxSourceSize`（绑定 `APP_FILE_MAX_SIZE`）。切分策略在构造时不依赖具体属性，在执行 `chunk` 时消费 `ChunkingOptions`。切分与解析实现不代表真实外部服务已验收或生产端到端完成。`FileFormat` 声明 TXT/MD/PDF/DOCX 路由类型，当前已实现 TXT 和 MD 生产解析器，PDF/DOCX 解析仍未实现。
- 切分策略（TokenBudgetKnowledgeChunker）实现规则：
  - FLAT 与 PARENT_CHILD 共用区间切分内核，不依赖源文件格式。
  - Token 计数基于本地 JTokkit 1.1.0（CL100K_BASE 编码），special token 字面量按普通文本处理，不调用网络。
  - 核心基于局部 UTF-16 区间坐标；普通文本按相同 headingPath 与显式硬边界（`boundaryBefore`）合并，CODE/TABLE 结构块单独隔离。
  - 块间使用固定 `\n\n`（keepSeparator=false 时为 `"  "`）连接；输入间隙跨 gap 时不伪造全文 offset（保守为 null）；单块原样等长来源可推导子 offset，完整块可沿用原范围；页码仅整块或单页可靠，部分多页块页码为 null；headingPath 的 null 与 `[]` 不混同。
  - keepSeparator=false 仅普通文本 CR/LF 一对一替换为空格，不删标点、不 trim；CODE/TABLE 始终保留换行。
  - 截断降级：段落（`\n\n`）-> 句子（`。？！?!` 与 `.`，避免数字点/域名误切）-> 次级（`\n` 或标点）-> 空白 -> 预算安全截断（`cap`）。
  - 短尾不丢，纯空白窗口可能不产出切片但必须覆盖非空内容；超 maxNumChunks（所有角色总和）明确失败。
  - Unicode: 不保证 Unicode 字素簇，但绝不拆分代理对（surrogate pair），校验合法 Unicode。
  - 父子切分：父块互不重叠（overlap=0）；子块支持目标 `chunkOverlap`（`0 <= chunkOverlap < chunkSize`），必须向后推进新内容（`covered`），硬边界/父块边界重置重叠；短内容单一 child 覆盖整段时不额外存相同父正文（`context = null`，直接无父）；父块只存 MySQL，child（RETRIEVAL）写向量。
  - CODE/TABLE 只保排版/按行或安全长度退化，没有结构化行/表头语义或代码 AST，无需文档 AST 或 schema 修改。
- 显式身份、文档、文件归属/状态、文件名末段扩展名和相应策略在创建版本前检查。文件归属不代表知识库权限。构建开始通过 READ_COMMITTED 短事务锁文档，拒绝任何已有 building，返回 `BUILD_ALREADY_RUNNING`。MAX+1、状态与计数沿用现有四表，不自动重试，不新增状态字段或 published 枚举。进程中断的 building 恢复尚未实现。
- 构建使用 `Propagation.NEVER` 拒绝外层数据库事务。生命周期入口以 READ_COMMITTED 短事务锁文档，MAX+1 创建版本；发布前校验活动快照、归属、全部片段真实总数和 RETRIEVAL 数，事务内完成 ready、源摘要、时间、文档活动指针/indexed 与旧版本 superseded。文件读取、策略执行和向量调用均不在数据库事务中。
- `application.knowledge.indexing` 按 `build / source / parse / chunk / vector` 聚合 RAG 专用合同与流程；各组内部直接放置相关类，不再增加 `service / port / model` 子包。`build` 包含 `KnowledgeIndexBuildService`、`KnowledgeBuildLifecycleService`、`KnowledgeProcessingRouter`、`KnowledgeBuildContext`、`KnowledgeBuildResult`、`KnowledgeBuildStage`、`KnowledgeBuildException`；`source` 包含 `KnowledgeSourceReader`、`PreparedSource`、`FileFormat`、`KnowledgeSourceException`；`parse` 包含 `KnowledgeDocumentParser`、`ParsedDocument`、`ContentBlock`、`ContentBlockType`；`chunk` 包含 `KnowledgeChunker`、`ChunkingMode`、`ChunkingOptions`、`ChunkDraft`、`ChunkGroupDraft`、`KnowledgeChunkGroupWriter`；`vector` 包含 `KnowledgeVectorIndex`、`KnowledgeVectorEntry`。四个基础 service、`SaveKnowledgeChunkCommand` 和四个基础仓储 port 保持原位与职责边界。
- `KnowledgeSourceReader` 预读原文件字节至受控内存，校验记录大小、实际大小上限和 SHA-256，产出可重复打开的 `PreparedSource`。源流仍由调用者关闭。该步骤不做 UTF-8 解码；由基础设施层 `TextKnowledgeDocumentParser` 与 `MarkdownKnowledgeDocumentParser` 委托 package-private `Utf8TextDecoder` 负责严格 UTF-8 校验与换行规整。`KnowledgeDocumentParser` 接口保持不变。
- TXT 与 Markdown 解析规则及安全预算：
  - TXT 规则：严格 UTF-8 解码（REPORT 模式，不猜测编码），拒绝 NUL 字符；增量解码内存流避免再次复制完整原字节，开头至多去除 1 个 BOM；CRLF、CR 统一规整为 LF；连续空白行（含普通空白及 NBSP 等 `Character.isSpaceChar`）切分自然段，段内保留换行与空格缩进、不执行 trim；全部内容块类别为 TEXT，标题路径与页码为 null，无硬边界；来源位置偏移为规整后全文的 UTF-16 substring 半开区间 `[start_offset, end_offset)`；全空白文档解析失败。
  - Markdown 规则：基于 CommonMark 与 GFM 表格扩展，只识别顶层块（top-level blocks）并保留原 text；HEADING 块保留 `#` 或 Setext 原文，标题路径提取标题名去结构符号但保留 inline 语法，标题每次构成硬边界；合法空标题保留为 TEXT，移除该级及更深路径但不虚构标题名；水平线不输出但下一 block 标记硬边界；CODE 块包含围栏/缩进完整保留；TABLE 块保留完整表格；列表与引用整个作为单个 TEXT 块，不递归重复输出嵌套段落，不用嵌套标题污染外层 headingPath；HTML/图片/公式等保留原文不执行下载或 OCR；reference 定义保留为 TEXT；不完整 md 按 CommonMark 规范容错解释；全空白或仅水平线解析失败；TXT/MD 当前输出 source 均为可靠 UTF-16 偏移；Markdown 使用 block 级 node spans 换算为全文行起始的 UTF-16 substring 半开区间 `[start_offset, end_offset)` 截取原文，不使用 renderer 回写；若 node 无 spans（`spans.isEmpty()` 或行索引越界）则明确解析失败抛出异常，不估算位置（应用 port 允许其他格式位置为 null）；未知页码为 null；行内 AST 采用轻量 parser 留 literal TEXT 避免大量 inline AST，语法留正文。
  - 固定安全预算与防御边界（硬编码常量，不加变量）：两解析器最大 100,000 输出 blocks；Markdown 最大 1,000,000 行、容器嵌套保护阈值 128。固定 guards（100k blocks、1M lines、嵌套深度 128）为硬编码解析防御限制，不是安全保证（不作为恶意 DoS 防护承诺）；超限明确拒绝，不截断内容。这些是解析预算，不是 RAG_MAX_NUM_CHUNKS 的切片限制，不保证所有输入在任意堆大小下可解析。
- `ParsedDocument`、`ContentBlock`、`ChunkDraft` 与 `ChunkGroupDraft` 是固定数据合同。每组可含一个无父 CONTEXT 和非空 RETRIEVAL 列表，也可只含 RETRIEVAL；FLAT 不允许输出父块，PARENT_CHILD 可返回无父短内容组。组作为数据库批次单位，不拆开父子关系。`RAG_CHUNK_SIZE`、`RAG_PARENT_CHUNK_SIZE`、`RAG_CHUNK_OVERLAP` 等选项由 `KnowledgeRagProperties` 绑定并构造 `ChunkingOptions` 传给切分策略。
- 构建按组分配连续全局序号、角色和父序号；`KnowledgeChunkGroupWriter.saveGroups` 将整批组转换为现有 `SaveKnowledgeChunkCommand`，并一次调用通过 Spring 注入的 `KnowledgeChunkService.saveBatch` 代理。writer 不声明事务；实际事务由基础服务的代理入口提供。`RAG_MAX_NUM_CHUNKS` 统计 CONTEXT 与 RETRIEVAL 总数。`RAG_BATCH_SIZE` 是数据库分组保存的目标大小，过大的单组独立保存、不拆组；向量只包含已保存 RETRIEVAL，并严格按配置大小成批写入（末批可不足）。片段保存先于向量写入。未知标题、Token 数、偏移和页码保持为空。
- 已实现的 Spring AI 向量适配器将已保存 RETRIEVAL 映射为 Document，沿用业务 UUID（为 32 位值补标准连字符），保留原始 `knowledge_chunk_id`，写入来源与父引用 metadata，并按 `revision_id` 精确过滤删除。适配器存在不表示已验收外部 Embedding/Qdrant 调用。
- 发布发生在全部片段和向量成功后，短事务校验归属、building、实际总片段及 RETRIEVAL 计数和活动快照，再原子更新 ready、源摘要、完成与发布时间、文档活动指针/indexed 与旧版本 superseded。当前没有检索实现；未来检索必须读取 MySQL 活动指针并按 revision_id 过滤。
- 普通构建失败标记 failed 并按当前 revision 尝试删除向量；首次失败文档 failed，重建失败保留旧活动指针与 indexed。MySQL 片段保留。向量删除成功才写 `cleaned_at`；删除失败保留原失败阶段/编码并追加固定摘要；删除已成功但完成事实写库失败时固定告警并返回清理未确认，不误记为向量删除失败。发布失败或提交结果不明时派生向量保留待核对，不盲目删除；确定的事务回滚不切换活动指针，不能把提交异常一概描述为已回滚。发布后旧向量清理失败不回滚新活动版本。
- MySQL 与 Qdrant 无共同事务；解析与切分实现不代表真实外部服务已验收或生产端到端完成，不声称完整补偿、幂等性、中断恢复或全面并发治理。日志与失败摘要不输出源正文、凭据或供应商异常正文。

### RAG 环境变量

| 变量 | 默认值与规则 |
| --- | --- |
| `APP_KNOWLEDGE_RAG_ENABLED` | false；启用才装配内部构建入口 |
| `SPRING_AI_MODEL_EMBEDDING`、`SPRING_AI_VECTORSTORE_TYPE` | none；启用分别为 openai、qdrant |
| `OPENAI_EMBEDDING_API_KEY` | 空，模型可回退公共 OPENAI_API_KEY，本地提供 |
| `OPENAI_EMBEDDING_MODEL` | text-embedding-3-small |
| `OPENAI_EMBEDDING_DIMENSIONS` | 空，使用模型默认维度；提供时为正整数，与集合一致 |
| `QDRANT_HOST`、`QDRANT_PORT`、`QDRANT_USE_TLS` | localhost、6334 gRPC、false |
| `QDRANT_API_KEY` | 空，按服务要求本地提供 |
| `QDRANT_COLLECTION_NAME`、`QDRANT_INITIALIZE_SCHEMA` | knowledge_chunks、false；开启初始化仅创建缺失集合 |
| `RAG_BATCH_SIZE` | 128，范围 1～1024；数据库组批目标且不拆组，向量使用精确批次上限 |
| `RAG_CHUNK_SIZE`、`RAG_MIN_CHUNK_SIZE_CHARS` | 512 正整数、200 非负；子块尺寸上限（Token，CL100K_BASE）与短块软阈值 |
| `RAG_PARENT_CHUNK_SIZE` | 2048 正整数；父上下文块尺寸上限（Token，必须 >= RAG_CHUNK_SIZE） |
| `RAG_CHUNK_OVERLAP` | 0 非负整数；子检索块重叠目标尺寸（Token，0 <= overlap < RAG_CHUNK_SIZE） |
| `RAG_MIN_CHUNK_LENGTH_TO_EMBED` | 0，当前只允许 0，作为策略选项 |
| `RAG_MAX_NUM_CHUNKS`、`RAG_KEEP_SEPARATOR` | 100000 正整数（全部角色总数）、true（传给策略，false 仅普通文本 CR/LF 等长替换为空格） |
| `RAG_PUNCTUATION_MARKS` | `.?!;。？！；` 与换行，非空策略选项；Properties 的 `\n` 表示换行 |

源大小复用 `APP_FILE_MAX_SIZE`，正且小于 Integer.MAX_VALUE 字节；源读取器预读原字节到内存，增加上限前重新评估内存占用。RAG 参数供当前切分策略消费且不写历史版本；更换模型、维度、集合或策略时另行评估兼容性与重建。

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

用户行为测试覆盖 HTTP、摘要、服务失败、真实 Mapper SQL、引用切换和请求身份。文件测试覆盖 H2 真 Mapper 生命周期、所有权和状态、实际流大小、摘要、关闭资源、SDK 边界、条件装配及真实容器 multipart 限制。普通测试强制关闭模型，使用 H2 和存储替身，不依赖本机 MySQL、Docker、外部对象存储或个人凭据。

知识库测试覆盖四表 H2 真 Mapper 和字段、同名多库、文件元数据归属、版本号递增及竞争冲突、标题 JSON 和可空位置、UTC 微秒，以及普通块兼容、两种角色及父引用往返、同批和已存父块解析、非法关系拒绝、片段排序、全部角色计数和事务失败回滚。生产解析器通过 `TextKnowledgeDocumentParserTest`、`MarkdownKnowledgeDocumentParserTest`、`Utf8TextDecoderTest` 与 `KnowledgeParserConfigurationTest` 单测真实调用覆盖（不调用网络），验证严格 UTF-8、换行规整、顶层块提取、标题路径与硬边界、安全预算及条件装配。生产切分器通过 `TokenBudgetKnowledgeChunkerTest` 真实单元测试覆盖 Token 预算、段落/句子/标点/空白回退、Unicode 代理对保护、短尾保留、重叠正进度、父子无重复、keepSeparator 语义与超限失败；配置绑定与策略装配由 `KnowledgeRagPropertiesTest`、`KnowledgeChunkerConfigurationTest` 和 `ChunkingOptionsTest` 覆盖。真实本地 parser/chunker、H2 Mapper/发布与文件/向量替身由 `KnowledgeChunkingBuildIntegrationTest` 覆盖完整构建链路；`KnowledgeTextParsingBuildIntegrationTest` 覆盖真实 parser 产出入库与发布流转。RAG 构建编排同时保留替身测试覆盖路由、原字节大小与摘要/流关闭、组式保存、数据库与向量独立批次、仅 RETRIEVAL 向量化、building 互斥、发布回滚、首次失败与重建失败、当前和旧版本清理事实、开关及依赖检查。Spring AI 向量适配测试使用 VectorStore 替身；不声称真实模型完整入库，不访问外部模型或 Qdrant。事务测试不使用测试外层事务；普通测试使用隔离 H2，不描述为真实 MySQL 或外部服务验收。

显式真实 MySQL 验收：先通过进程环境变量配置上表中的 `USER_TEST_DB_*`（仅写入 `.env` 不会生效），再运行 `mvn -Pmysql-it verify`。该 profile 执行正式 schema 和完整 HTTP CRUD，仅清理本次创建的测试记录。H2 验证不能被描述为 MySQL 验证。实际启动验证仍应请求 `GET /api/health`。

## 文档同步

现行文档按用途维护，只保留当前有效的使用方式、实现说明和参考资料：

- `README.md`：项目定位、最短启动步骤与入口，不堆积详细变量表和历史执行结果。
- `docs/README.md`：文档导航与阅读顺序；新增或移动文档时同步更新并检查相对链接、页内锚点。
- `docs/guides/getting-started.md`、`docs/guides/testing.md`：运行步骤、测试命令及验证边界。
- `docs/architecture.md`、`docs/modules/user.md`、`docs/modules/file.md`、`docs/modules/knowledge.md`：四层结构、公共能力及模块的内部调用、规则、流程、源码与测试入口。模块说明按学习目标与范围、调用方式、核心流程与规则、失败边界、源码与测试入口组织。
- `docs/reference/configuration.md`：配置加载语义、环境变量和默认值；运行指南与模块文档通过链接引用详细定义。
- `docs/reference/api/`：公开 HTTP 合同；`README.md` 为接口总览，`common.md` 维护调用约定、身份、请求标识和错误响应，`health.md`、`users.md`、`files.md` 分别维护健康、用户和文件接口。内部知识库服务不列为公开 API。
- `docs/reference/database/knowledge.md`：当前知识库四表字段、可空性、索引、关系和设计限制，不记录旧项目迁移过程。

临时方案统一放在项目根目录 `.work/plans/`，由 `.gitignore` 排除，不暂存或提交；执行完成、所需功能检查通过且现行文档同步后删除，未完成时暂留本地。文档区不建立历史归档，不保留任务执行记录、审查报告或历史测试结果。仍适用的业务约束和设计理由直接维护在对应现行说明中；实际验证结果在当次交付中说明，不将编译成功描述为测试通过。

源码注释优先解释业务规则、更新意图、归属校验、流资源所有权、UTC 转换和外部失败边界，避免逐行复述代码。port 的输入输出与资源释放合同发生变化时，同步其注释和模块说明。

以下变化必须同步修改对应现行文档、README 入口和本文件：

- 技术栈或依赖版本变化。
- 模块结构、职责或依赖方向变化。
- 启动命令变化。
- 环境变量变化。
- API 路径或响应变化。
- 测试和构建命令变化。

公开 HTTP 接口文档维护在 `docs/reference/api/`，README 提供入口。新增或修改请求参数、校验规则、状态码、响应字段或错误码时，同步对应业务接口文档及公共约定；只记录生产源码中实际存在的接口，不把测试 Controller 或内部 Java 合同列为公开接口。

## Git 操作与交付边界

- 代理只按用户当前声明的任务工作。完成未声明的附加任务后，即使测试和构建通过，也不得自行执行 `git add`、`git commit`、`git push`、创建 Pull Request 或合并；验证通过不等于获得提交授权。
- 当前任务默认在现有分支和工作区中完成。代理不得独自创建新分支，也不得为了隔离任务自行切换到新分支；需要新分支时先说明原因、名称和影响，等待用户明确授权或由用户先创建。
- 用户明确要求提交、推送、创建 Pull Request、合并或分支操作时，仍需只处理该声明范围，并在执行前检查差异和验证结果。未明确授权时保留工作区改动，在交付说明中列出文件和验证结果。

## 代理交付要求

完成任务时，最终说明：

1. 修改了哪些文件，以及每个文件的目的。
2. 执行了哪些验证命令，以及实际结果。
3. 未完成的验证、已知限制或需要用户决策的事项。

不要声称未实际运行的测试、构建或接口验证已经通过。
