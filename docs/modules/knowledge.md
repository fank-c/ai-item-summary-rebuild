# 知识库模块

当前提供四表持久化、内部基础服务和 txt/md 同步 RAG 入库。学习目标是理解 Spring AI Reader、TokenTextSplitter、Embedding 与 Qdrant 的组合，以及数据库版本发布与外部索引之间的失败边界。

返回 [文档导航](../README.md)。字段见 [知识库表结构](../reference/database/knowledge.md)，参数见 [配置说明](../reference/configuration.md#txtmd-rag-入库)，运行见 [运行指南](../guides/getting-started.md#6-启用-txtmd-入库)，验证见 [测试说明](../guides/testing.md)。

## 学习目标与范围

首版读取已经完成上传、属于调用者的 UTF-8 txt/md 文件。txt 使用 `TextReader`，md 使用 `MarkdownDocumentReader`，统一使用一个 `TokenTextSplitter`；Spring AI `QdrantVectorStore.add` 调用 OpenAI EmbeddingModel 并写入 Qdrant。

没有知识库 HTTP、知识库权限、PDF/DOCX/OCR、异步任务、检索、聊天或重排序。MySQL 是版本可检索性的权威；Qdrant 保存可重新生成的派生数据。应用层和领域层不依赖 Spring AI 类型或 SDK。

## 内部调用方式

| 服务 | 入口 | 行为 |
| --- | --- | --- |
| `KnowledgeBaseService` | `create(name)`、`findById(id)`、`findAll()`、`rename(id,name)` | 名称 trim 后非空白，最多 100 个 Java 字符；允许同名多库 |
| `KnowledgeDocumentService` | `register(actorUserId,knowledgeBaseId,fileId)`、详情与按库查询 | 只查文件元数据，创建 pending 文档 |
| `KnowledgeRevisionService` | `create(documentId)`、详情与按文档查询 | 基础记录入口，MAX+1 创建 building；不执行构建或发布 |
| `KnowledgeChunkService` | `saveBatch(revisionId,commands)`、详情与按版本查询 | building 版本追加片段，插入与实际总数共同提交 |
| `KnowledgeIndexBuildService` | `build(actorUserId,knowledgeDocumentId)` | 完整读取、切分、保存、向量化、发布；只在 RAG 开关启用时装配 |
| `KnowledgeBuildLifecycleService` | 构建创建、发布、失败及清理事实记录 | 内部短事务，由构建服务协调；不调用外部索引 |

在 Spring 注入的组件中调用：

```java
var base = knowledgeBaseService.create("学习资料");
var document = knowledgeDocumentService.register(actorUserId, base.id(), uploadedFileId);
var result = knowledgeIndexBuildService.build(actorUserId, document.id());
// result.revisionId() 为新活动版本；result.chunkCount() 为已保存的片段数。
// result.oldRevisionCleanupCompleted() 单独表示旧向量清理是否完成。
```

构建入口自行创建版本。调用 `build` 前不要调用基础 `KnowledgeRevisionService.create`，已有 building 会导致 `BUILD_ALREADY_RUNNING`。基础版本与片段入口仍供独立学习和内部数据操作使用，不能绕过构建服务伪造索引成功。

通过 Spring 注入的代理调用构建入口，禁止外层数据库事务包裹 `build`。该入口使用 `Propagation.NEVER` 拒绝外层事务，确保对象存储、Embedding 和 Qdrant 操作不会持有整个流程的数据库事务。不要自行 `new` 事务服务或绕过代理。

结果与错误都是内部 Java 合同，尚无公开 HTTP 错误映射。构建开关关闭时没有 `KnowledgeIndexBuildService` Bean；基础服务和健康接口仍可使用。

## 核心流程与规则

### 登记与身份

显式身份不裁剪，缺失抛 `AuthenticationRequiredException`，超过 64 个 Unicode 码点拒绝。先查文档，再用 `FileContentService.requireOwnedProcessable` 检查文件归属和状态。末段扩展名忽略大小写，只有 txt/md 可构建；PDF、DOCX 和无扩展名在创建版本前拒绝。

登记与构建都不调用文件 Mapper。文件要求未删除、status=1、completed、所有者匹配。知识库不保存 owner_user_id，源文件归属不等于知识库访问授权。重复库内文件登记返回 `DOCUMENT_ALREADY_REGISTERED`，数据库联合唯一索引兜底。

### Reader 与切分

构建服务用 try-with-resources 关闭 `FileContentResource`，将 `DigestInputStream` 交给内容 port。内容适配器完整读取原始字节，限制实际大小，严格验证 UTF-8，再交给受控内存 `Resource` 和 Spring AI Reader。不会接受用户 URL，也不会让 Reader 的容错解码悄悄替换损坏字符。

Markdown 配置保留代码块与引用，水平分隔线产生文档边界。Spring AI 2.0.1 Markdown Reader 使用 JVM 默认字符集，适配器要求其为 UTF-8；Java 21 的常规默认值满足此要求。Markdown 标题元数据可以随处理结果返回，但不会被冒充完整有序 `heading_path`。

Splitter 固定 `CL100K_BASE`，默认 512 Token、`minChunkSizeChars=200`、`minChunkLengthToEmbed=0`、最多 100000 片段、保留分隔符。标点包含 `.?!;。？！；` 与换行，无重叠窗口。200 字符控制超出 Token 目标时的标点截断，不是最终片段最小长度。

组件达到分片上限后可能追加整段余文。适配器检查全局片段数和每片 Token 粒度，超过上限就失败，不发布过大片段、不自定义重切。精确 Token 检查只用于粒度防线，数据库 `token_count` 仍为空，不宣称它是 Embedding 模型的精确计数。

处理完比较实际 SHA-256 与打开资源时的文件摘要。摘要不匹配、空白或无有效片段、未消费完整原始流均失败。首版允许整个源文件及 Reader 文档读入内存；实际原始字节上限跟随 `APP_FILE_MAX_SIZE`，默认 20 MB。

### 片段与向量批次

按处理顺序从 0 分配连续序号，默认每批 128 个片段。先调用 `KnowledgeChunkService.saveBatch`，取得已提交的业务片段 ID，再映射向量输入并调用索引 port。首版标题路径、Token 数、偏移和页码全部为空，正文采用 Reader/Splitter 的实际输出。

片段 ID 保持数据库中的 32 位 UUID。Spring AI Qdrant 2.0.1 使用 `UUID.fromString`，适配器为同一个 UUID 补连字符作为 Document ID；没有生成第二个标识，metadata 的 `knowledge_chunk_id` 仍保留原始 32 位值。向量元数据还包含库、文档、revision、文件、序号、名称、MIME 和源 SHA-256。

基础片段批次仍采用追加语义。输入必须非空，正文非空白，序号非负且批内唯一。已存序号冲突为 `CHUNK_SEQUENCE_CONFLICT`，非 building 为 `REVISION_NOT_BUILDING`。每批使用 READ_COMMITTED 并锁住版本，插入和实际总数更新共同回滚；同版本并发追加串行执行。基础设施检查实际连接隔离级别，拒绝不兼容外层事务。

### 构建互斥与版本发布

构建创建在短事务内先锁文档，再检查 building 并分配 MAX+1 版本号。同文档同时开始构建只有一个成功，另一个报 `BUILD_ALREADY_RUNNING`。不自动重试，达到 int 上限报 `REVISION_NUMBER_EXHAUSTED`。进程中断可能留下 building，后续人工恢复方案尚未实现。

全部数据库片段与向量写入成功后，在独立数据库事务中锁文档与版本，确认归属、building、真实片段数和构建开始时的活动版本关系。新版本变为 ready，填写源摘要、completed_at、published_at，更新 active_revision_id 与 indexed 并清空文档失败摘要；旧活动 ready 变为 superseded。

这些数据库更新共同提交或回滚。版本仍使用 building、ready、failed、superseded，没有 published 枚举。`ready` 单独不表示可检索；后续检索必须先读取文档 `active_revision_id`，再按该 `revision_id` 过滤 Qdrant。

数据库与 Qdrant 没有共同事务。发布后的旧向量清理在数据库事务外执行，外部删除成功后才写 `cleaned_at`。清理失败保留新活动版本，结果的 `oldRevisionCleanupCompleted=false`，并在旧版本记录固定的清理失败摘要。

## 失败边界

| 阶段 | 可观察结果 |
| --- | --- |
| 身份、文档、归属、扩展名 | 拒绝调用，不创建版本 |
| 文件打开、读取、UTF-8、空白、摘要与粒度 | 新版本 failed，不发布；保留失败记录 |
| MySQL 片段批次 | 当前批次回滚，之前批次保留，版本 failed |
| Embedding 或 Qdrant 写入 | 版本 failed，按当前 revision 删除全部已写入及部分成功的向量 |
| 当前版本向量清理失败 | 原失败阶段和编码保留，追加清理失败摘要，cleaned_at 为空 |
| 发布事务失败 | 活动指针不切换，尝试记录失败，派生向量保留待核对，并记录固定告警 |
| 旧版本清理失败 | 新版本继续活动，旧版本 superseded，清理结果为 false |

首次构建失败时文档为 failed；已有活动版本的重建失败时文档保持 indexed，旧指针不变，最近失败摘要单独更新。失败版本及片段保留供诊断，不删除 MySQL 片段。

构建失败抛 `KnowledgeBuildException`，含内部 stage、code 和 `cleanupCompleted`。失败摘要仅使用固定安全文本，普通日志不输出正文、API Key、完整模型响应或供应商异常消息。若数据库在失败处理时也不可用，失败或清理事实可能无法落库；服务继续返回失败并记录固定告警，不声称已完成治理。

没有自动补偿、幂等治理、跨系统原子提交、完整并发协调或中断恢复；不同模型、维度和集合的兼容性需要另行评估。

## 字段、位置与时间

基础片段入口支持有序 `List<Heading>`，JSON NULL 与空数组分别保留，列表复制为不可修改快照。使用既有 Jackson 3；MySQL 绑定 JSON 文本，H2 绑定原生 UTF-8 JSON 字节。

调用方提供的 Token 数必须非负。偏移同时为空或满足 `0 <= start_offset < end_offset`，相对处理后全文的 UTF-16 位置；页码同时为空或满足 `1 <= page_start <= page_end`。未知值不推算，四表未保存完整处理后全文或 OCR 坐标。txt/md 自动构建均不填这些位置。

ID 使用 32 位 UUID；时间使用共享 Clock、Instant 与 UTC 微秒。版本表为 `created_at`，其余知识表为 `create_at`。

## 测试与学习入口

| 内容 | 源码或测试入口 |
| --- | --- |
| 基础与构建用例 | [service](../../src/main/java/com/item/aisummary/application/knowledge/service/) |
| 应用输入、输出与外部合同 | [command](../../src/main/java/com/item/aisummary/application/knowledge/command/)、[result](../../src/main/java/com/item/aisummary/application/knowledge/result/)、[port](../../src/main/java/com/item/aisummary/application/knowledge/port/) |
| 四表领域、规则与异常 | [domain.knowledge](../../src/main/java/com/item/aisummary/domain/knowledge/) |
| 条件装配与参数 | [config](../../src/main/java/com/item/aisummary/infrastructure/knowledge/config/) |
| Spring AI Reader、Splitter、Qdrant 适配 | [ai](../../src/main/java/com/item/aisummary/infrastructure/knowledge/ai/) |
| 真实 Mapper 与 JSON | [persistence](../../src/main/java/com/item/aisummary/infrastructure/knowledge/persistence/) |
| 基础规则与字段 | [KnowledgeRulesTest](../../src/test/java/com/item/aisummary/domain/knowledge/rule/KnowledgeRulesTest.java)、[KnowledgePersistenceTest](../../src/test/java/com/item/aisummary/infrastructure/knowledge/persistence/repository/KnowledgePersistenceTest.java) |
| 登记、归属与版本号竞争 | [KnowledgeServicesTest](../../src/test/java/com/item/aisummary/application/knowledge/service/KnowledgeServicesTest.java) |
| 追加、实际计数与失败回滚 | [KnowledgeChunkServiceTest](../../src/test/java/com/item/aisummary/application/knowledge/service/KnowledgeChunkServiceTest.java) |
| 构建锁、完整发布与回滚 | [KnowledgeBuildLifecycleServiceTest](../../src/test/java/com/item/aisummary/application/knowledge/service/KnowledgeBuildLifecycleServiceTest.java)、[KnowledgeBuildTransactionBoundaryTest](../../src/test/java/com/item/aisummary/application/knowledge/service/KnowledgeBuildTransactionBoundaryTest.java) |
| 完整入库、摘要、批次和失败清理 | [KnowledgeIndexBuildServiceTest](../../src/test/java/com/item/aisummary/application/knowledge/service/KnowledgeIndexBuildServiceTest.java) |
| Reader 与粒度 | [SpringAiKnowledgeContentProcessorTest](../../src/test/java/com/item/aisummary/infrastructure/knowledge/ai/SpringAiKnowledgeContentProcessorTest.java) |
| 向量 ID、元数据、过滤和错误 | [SpringAiKnowledgeVectorIndexTest](../../src/test/java/com/item/aisummary/infrastructure/knowledge/ai/SpringAiKnowledgeVectorIndexTest.java) |
| 开关与缺少依赖 | [KnowledgeRagConfigurationTest](../../src/test/java/com/item/aisummary/infrastructure/knowledge/config/KnowledgeRagConfigurationTest.java) |

普通测试只使用隔离 H2、真实本地 Spring AI Reader/Splitter 和外部索引替身，不访问 OpenAI、Qdrant 或对象存储。`mysql-it` 仍只覆盖用户。真实 RAG 验收需要专用 MySQL、Qdrant 集合和模型凭据，步骤与清理范围见 [测试说明](../guides/testing.md#真实-rag-验收)。
