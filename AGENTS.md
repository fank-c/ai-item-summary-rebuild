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

当前阶段维护基础工程、用户、核心文件、知识库基础服务和 txt/md 同步 RAG 入库：用户资料 CRUD、BCrypt 密码摘要、跨模块用户存在性查询、模拟请求身份、公共错误处理，文件上传、下载签名、删除、内部内容读取，以及知识库四表、Spring AI Reader/Splitter、OpenAI Embedding、Qdrant 索引与版本发布。元数据使用 MySQL；文件存储可选 MinIO 或阿里云 OSS。真实登录、权限、账户启停、客户端直传、可用性框架、知识库 HTTP、PDF/DOCX/OCR 入库、异步任务、检索和问答不在本阶段范围。新增其他基础设施或业务前，必须有明确的用户需求。

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
│   ├── knowledge/     # service、command、result、port、exception
│   └── auth/          # 最小请求身份、身份 ID 规则及缺少身份异常
├── domain/
│   ├── user/          # model、exception
│   ├── file/          # model、rule、exception
│   └── knowledge/     # model、value、enums、rule、exception
└── infrastructure/
    ├── user/          # config、persistence、reference
    ├── file/          # config、persistence、storage
    ├── knowledge/     # config、persistence、ai
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
- 保留 `web / application / domain / infrastructure` 四层结构，按“层 → 业务 → 职责”组织，例如 `web.user.controller`、`application.knowledge.service` 和 `domain.knowledge.model`；对话、RAG 等功能沿用相同方式。
- Web 按 `controller / request / response` 分类；应用按 `service / command / result / port` 分类，跨模块用户引用与文件内容读取分别集中在 `reference / content`。领域按实际职责使用 `model / value / enums / rule / exception`，不把不同层的数据对象统一塞入 DTO 包。
- 基础设施装配与配置绑定位于各业务 `config`；MyBatis 按 `persistence.entity / mapper / repository / typehandler` 分包，厂商存储适配留在 `storage`。共享 UTC 类型处理器位于 `infrastructure.persistence.typehandler`，共享时钟位于 `infrastructure.time.ClockConfiguration`，业务模块通过注入使用时钟。
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

- 当前使用 `spring-ai-starter-model-openai`、`spring-ai-markdown-document-reader` 和 `spring-ai-starter-vector-store-qdrant`，版本均由现有 2.0.1 BOM 管理。
- 用例通过应用层定义的外部能力接口调用 AI，例如摘要功能使用 `SummaryGenerator`。基础设施适配依赖 Spring AI 抽象接口，供应商特有实现封装在该边界内；应用层和领域层不直接依赖 `ChatModel`、`ChatClient` 或供应商 SDK。
- 默认保持 `spring.ai.model.chat=none`、`spring.ai.model.embedding=none`、`spring.ai.vectorstore.type=none` 和 RAG 构建关闭，使没有 API Key 或 Qdrant 的环境可以启动和测试。构建开启时要求显式 OpenAI Embedding 与 Qdrant，缺少依赖拒绝启动；关闭时不装配 `KnowledgeIndexBuildService`，基础服务和健康检查仍可用。
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

- 已实现知识库四表领域、MyBatis 持久化、内部基础服务和 txt/md 同步构建。字段见 `docs/reference/database/knowledge.md`，调用见 `docs/modules/knowledge.md`。没有知识库 HTTP、权限、删除、异步任务、PDF/DOCX/OCR、检索或聊天。
- 服务按用例组织，无 Service/Impl 套层。`KnowledgeBaseService` 创建、详情、列表和改名；名称 trim 后非空白且最多 100 个 Java 字符，允许多库同名。业务 ID 为 32 位 UUID，时钟复用现有 Clock 并截断到微秒。
- `KnowledgeDocumentService.register(actorUserId,knowledgeBaseId,fileId)` 先校验显式身份及库存在，再通过 `FileContentService.requireOwnedProcessable` 校验源文件。只查询元数据；不得读取正文、调用文件 Mapper 或将文件归属视为知识库授权。新文档 status=1、index_status=pending，活动版本和失败摘要为空。
- 重复库内文件登记报 `DOCUMENT_ALREADY_REGISTERED`，数据库唯一索引兜底。`KnowledgeRevisionService.create(documentId)` 先确认文档存在，使用 MAX + 1 创建 building 版本；竞争报 `REVISION_NUMBER_CONFLICT`，达到 int 上限报 `REVISION_NUMBER_EXHAUSTED`，不自动重试。新版本 chunk_count=0，created_at/started_at 为进入 building 的时间，摘要、失败信息和其他生命周期时间为空，不修改文档活动版本。
- `KnowledgeChunkService.saveBatch(revisionId,commands)` 只接受 building 版本。输入没有 revisionId/片段 ID，所有片段由本次入口确定归属并生成新 ID。批次非空、正文非空白、序号非负、批内序号唯一；已存序号冲突报 `CHUNK_SEQUENCE_CONFLICT`，非 building 报 `REVISION_NOT_BUILDING`。不提供正文更新接口。
- 批次采用追加语义。通过 Spring 注入的服务调用事务入口，使用 READ_COMMITTED 并锁住目标版本；基础设施检查实际连接隔离级别，不兼容的外层事务在写入前明确失败。插入和实际总数更新在同一事务中，写入行数必须符合预期。数据库异常和业务冲突继续传播并回滚整批，兼容外层事务回滚也共同回滚；同版本并发批次串行执行。不自行 new 事务服务，也不添加通用事务框架。
- `heading_path` 保持有序标题列表，标题层级为正、标题非空白；JSON NULL 与空数组分别还原。使用既有 Jackson 3 和专用类型处理器；MySQL 绑定 JSON 文本，H2 绑定原生 UTF-8 JSON 字节，避免把数组保存成字符串。
- token_count 可空，有值时非负。偏移同时空或满足 0 <= start_offset < end_offset；页码同时空或满足 1 <= page_start <= page_end。正文保留原样，不以片段长度限制全文偏移，不填充估算的 Token 数量或虚构位置。
- 详情与按关联列表查询对不存在的关联抛 `KnowledgeNotFoundException`；合法但无子记录时返回空列表。版本按版本号升序、片段按 sequence_no 升序。内部异常尚未定义公开 HTTP 错误合同。
- 知识库只保存自身信息，移除 `owner_user_id` 及其唯一索引。用户访问关系留待后续权限表管理，本次不创建权限表，也不将缺少所有者字段解释为公开访问。
- 保留库内文件唯一、文档内版本号唯一、版本内片段序号唯一。沿用逻辑关联，不新增物理外键或级联删除；文件归属与活动版本一致性仍需业务校验。
- 版本表保留整体状态、失败摘要、源文件摘要、片段数量和时间。不保存 `parser_strategy`、`chunking_strategy`、`chunk_size`、`chunk_overlap`、`quality_status`、`normalization_strategy`、`vector_route_key`，片段表同样不保存切片策略；旧实体和 Mapper 不可原样迁入。
- 不新增 `stage_status`，移除 `cleanup_status`。保留 `cleaned_at` 作为清理完成时间；为空不区分未清理、无需清理或清理失败。
- 保留偏移、页码和标题信息，但未知时为空。UTF-16 偏移相对于处理后全文，不是原文件字节或 PDF 坐标；源全文持久化、OCR 坐标与 Word 位置在对应需求实现时另行确定。
- 正式与 H2 schema 同步维护字段、可空性和索引。生产脚本仍手动执行，`IF NOT EXISTS` 不升级旧表；不自动导入历史数据或清理既有向量集合。

### txt/md RAG 构建约定

- `KnowledgeIndexBuildService.build(actorUserId,knowledgeDocumentId)` 为唯一完整构建入口；应用通过 `KnowledgeContentProcessor`、`KnowledgeVectorIndex` 自有合同协调，不依赖 Spring AI Document/Reader/Splitter/EmbeddingModel/VectorStore 或 SDK。
- 显式身份先校验；先查询文档与可处理文件元数据，再按文件名末段扩展名校验 txt/md。其他格式、归属和状态错误在创建版本前拒绝。文件归属不代表知识库权限。
- 构建开始通过 READ_COMMITTED 短事务锁文档，拒绝任何已有 building，返回 `BUILD_ALREADY_RUNNING`。MAX+1、状态与计数沿用现有四表，不自动重试，不新增状态字段或 published 枚举。进程中断的 building 恢复尚未实现。
- `build` 使用 `Propagation.NEVER` 拒绝外层数据库事务，生命周期与片段服务均通过 Spring 代理调用；不把文件读取、Embedding、Qdrant 或清理放入数据库事务。
- 同一原始流由 DigestInputStream 计算实际 SHA-256；内容处理消费完整字节，实际大小上限跟随 APP_FILE_MAX_SIZE。先严格验证 UTF-8，再通过受控内存 Resource 使用 TextReader 或 MarkdownDocumentReader；Markdown Reader 要求 UTF-8 默认字符集。摘要不匹配、空白与未完整消费失败，不发布。
- Markdown 保留代码块与引用，并按水平分隔线创建文档边界；只使用组件结构解析，不实现自定义 Markdown 或切片算法。唯一 TokenTextSplitter 固定 CL100K_BASE，默认 512/200/0/100000、keepSeparator=true，包含中英文标点与换行，无重叠。检查总片段数及最终 Token 粒度，拒绝上限后追加的过大余片。
- 自动构建序号从 0 连续分配，默认每批 128；先保存片段再写向量。heading_path、token_count、偏移、页码保持空，不将 Reader 元数据冒充完整路径或精确位置。
- Qdrant 使用同一片段业务 UUID；Spring AI 2.0.1 要求标准 UUID 字符串，适配器为 32 位 ID 补连字符，metadata.knowledge_chunk_id 保留原值，不生成新 ID。metadata 还包含知识库、文档、revision、文件、序号、名称、MIME、source_sha256；清理严格按 revision_id 等值过滤。
- 全部片段和向量成功后才调用发布短事务：验证归属、building、真实片段计数及活动关系，原子更新 ready、源摘要、完成与发布时间、文档 active_revision_id/indexed，清空文档失败摘要，旧活动 ready 变 superseded。后续检索必须从 MySQL 读取活动指针，再按 revision_id 过滤；ready 或 Qdrant 中存在向量不足以表示可检索。
- 普通构建失败标记 failed 并按当前 revision 删除向量；首次失败文档 failed，重建失败保留 indexed 与旧指针。保留失败版本及 MySQL 片段。清理成功才写 cleaned_at；清理失败保留原失败阶段/编码并追加摘要，异常 cleanupCompleted=false。
- 发布事务失败不切换活动指针，派生向量保留待核对并告警。发布后旧向量清理失败不回滚新活动版本，结果 oldRevisionCleanupCompleted=false，旧版本保存 cleanup 失败摘要。数据库也不可用时可能无法保存失败事实，继续返回失败并使用固定告警。
- MySQL 与 Qdrant 无共同事务，不声称完整补偿、幂等性、中断恢复或全面并发治理。日志与失败摘要不输出源正文、Prompt、模型响应、凭据或供应商异常正文。

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
| `RAG_BATCH_SIZE` | 128，范围 1～1024 |
| `RAG_CHUNK_SIZE`、`RAG_MIN_CHUNK_SIZE_CHARS` | 512 正整数、200 非负 |
| `RAG_MIN_CHUNK_LENGTH_TO_EMBED` | 0，首版只允许 0，防止丢弃短内容 |
| `RAG_MAX_NUM_CHUNKS`、`RAG_KEEP_SEPARATOR` | 100000 正整数、true |
| `RAG_PUNCTUATION_MARKS` | `.?!;。？！；` 与换行，非空；Properties 的 `\n` 表示换行 |

源大小复用 `APP_FILE_MAX_SIZE`，正且小于 Integer.MAX_VALUE 字节；提高文件上限时重新评估分片数与内存占用。RAG 参数不写历史版本，更换模型、维度、集合或切片规则时另行评估兼容性与重建。

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

知识库测试覆盖四表真实 Mapper 和字段、同名多库、文件元数据归属、版本号递增及竞争冲突、标题 JSON 和可空位置、UTC 微秒，以及片段排序、计数和事务失败回滚。知识库测试另外覆盖真实 Reader/Splitter、UTF-8、源摘要、流关闭、连续批次与向量元数据、building 互斥、发布回滚、首次失败与重建失败、当前和旧版本清理事实、开关与依赖检查。模型/Qdrant 使用替身，不进行外部调用。事务测试不使用测试外层事务；普通测试仍只访问隔离 H2，不描述为真实 MySQL 或 OpenAI/Qdrant 验证。

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
