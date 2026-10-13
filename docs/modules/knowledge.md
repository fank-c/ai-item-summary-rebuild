# 知识库模块

当前提供知识库四表持久化、内部基础服务，以及固定 RAG 构建骨架。骨架定义了解析/分块合同、原字节读取、分组片段保存、向量适配和版本生命周期，但**没有生产文档解析器或分块器**。因此目前不能通过配置完成真实 txt/md 入库。

返回[文档导航](../README.md)。表字段见[知识库表结构](../reference/database/knowledge.md)，环境变量及原有默认值见[配置说明](../reference/configuration.md#rag-构建配置)，运行与范围见[运行指南](../guides/getting-started.md#6-rag-构建骨架状态)，测试边界见[测试说明](../guides/testing.md)。

## 学习目标与范围

本模块用于学习内部知识库服务、chunk 父子数据合同、Spring 事务下的版本生命周期，以及应用如何通过 parser/chunker/vector ports 组织可替换处理步骤。Spring AI 向量适配器把已保存的 RETRIEVAL 片段转换为向量文档。

以下部分尚未实现：txt/md 或其他格式的生产 parser、FLAT/PARENT_CHILD 生产 chunker、UTF-8 文本解析、真实端到端入库验收、检索和问答。`FileFormat` 中列出 TXT/MD/PDF/DOCX 仅表示可路由的格式枚举，不代表相应解析器存在。当前无 `TextReader`、`MarkdownDocumentReader` 或 `TokenTextSplitter` 生产流程。

没有知识库 HTTP、权限管理、删除、异步任务、PDF/DOCX/OCR 解析、检索、聊天或重排序。文件归属检查不等于知识库授权。MySQL 保存权威版本状态；向量索引是外部派生数据，两者没有共同事务。

## 内部调用方式

| 服务/合同 | 入口与职责 |
| --- | --- |
| `KnowledgeBaseService` | `create(name)`、`findById(id)`、`findAll()`、`rename(id,name)`；名称裁剪后非空白且最多 100 个 Java 字符，允许同名多库 |
| `KnowledgeDocumentService` | `register(actorUserId,knowledgeBaseId,fileId)`、详情与按库查询；校验显式身份、知识库存在和源文件所有权，只查询元数据 |
| `KnowledgeRevisionService` | `create(documentId)`、详情与按文档查询；基础版本入口用 MAX+1 创建 building，不执行解析、索引或发布 |
| `KnowledgeChunkService` | `saveBatch(revisionId,commands)` 是基础片段批量持久化入口；只接受 building 版本，不包含构建组合同 |
| `KnowledgeIndexBuildService` | `build(actorUserId,documentId)` 默认 FLAT；`build(actorUserId,documentId,mode)` 显式选择已注册的 FLAT 或 PARENT_CHILD 分块策略 |
| `KnowledgeBuildLifecycleService` | begin/publish/fail/清理记录；短事务管理版本状态，不调用 parser、chunker 或向量后端 |
| `KnowledgeChunkGroupWriter` | 构建侧组合同适配器；为组分配序号、角色和父序号，转换为既有命令并一次调用 `KnowledgeChunkService.saveBatch` |
| `KnowledgeDocumentParser` / `KnowledgeChunker` / `KnowledgeVectorIndex` | 按 parse/chunk/vector 能力组织的应用层扩展合同；当前无生产 parser/chunker |

构建入口和 `KnowledgeChunkGroupWriter` 只在 RAG 条件配置启用时装配。当前生产配置没有 parser/chunker Bean，因此 RAG 保持关闭；显式开启时缺少任一必要策略会拒绝启动，而不是装配空实现。即使补齐模型和 Qdrant 配置，也不能单独启用真实 txt/md 入库。

通过 Spring 注入的代理使用构建服务，不能自行 `new` 事务服务或在外层事务中调用 `build`。构建方法使用 `Propagation.NEVER`；构建生命周期与片段写入各自在短事务中执行。组 writer 本身没有事务注解，通过注入的 Spring `KnowledgeChunkService` 代理调用一次 `saveBatch`，由基础服务事务边界保障整批原子写入。构建结果与异常均为内部 Java 合同，当前没有公开 HTTP 错误映射。

### 登记与基础服务规则

- `KnowledgeDocumentService.register` 校验显式身份与知识库存在，再用 `FileContentService.requireOwnedProcessable` 检查源文件元数据、状态和归属；不读取正文、不调用文件 Mapper。文件归属不构成知识库授权。
- actor ID 不裁剪，必填且最多 64 个 Unicode 码点。文件名末段扩展名用于 `FileFormat` 查找，扩展名大小写不敏感；格式枚举不等同于 parser 已注册。
- 同一知识库不能重复登记同一文件，冲突码为 `DOCUMENT_ALREADY_REGISTERED`，数据库联合唯一约束兜底。登记产生 pending 文档，活动版本及失败摘要为空。
- `KnowledgeRevisionService.create` 是基础记录入口，版本号以 MAX+1 分配，遇竞争冲突不自动重试；不能先手动创建版本再调用完整构建入口。
- 片段输入必须非空、正文非空白、序号非负且批内唯一。所有角色共同占用版本内序号；正文保留原样，标题、token_count、位置等未知值保持 null。保存服务只接受 building 版本，父引用及所有片段写入、实际总数更新在 READ_COMMITTED 短事务中完成并回滚。

## 固定构建流程与合同

### 源文件与解析输入

构建先校验显式 actor ID、文档和可处理源文件，再根据名称末段扩展名选择 `FileFormat`，并在创建版本前查找 parser 与 chunker。模式不由文件格式推断：两参数调用默认为 `ChunkingMode.FLAT`，第三参数可指定 `PARENT_CHILD`；缺少指定策略会失败，不自动降级。FLAT 输出中不允许有上下文父块，PARENT_CHILD 可为短内容返回无父组。

`application.knowledge.indexing.source` 中的 `KnowledgeSourceReader` 将源流读至受控内存，校验记录大小、实际大小上限和原字节 SHA-256，生成可重复打开的 `PreparedSource`。资源流由调用者通过 try-with-resources 关闭。此步骤只准备和校验字节，不做 UTF-8 解码；未来文本 parser 必须自己执行严格 UTF-8 校验。当前没有生产 parser。

`application.knowledge.indexing.parse` 中的 `ParsedDocument` 与有序 `ContentBlock` 表达 parser 的结构化输出；内容类别、标题路径、边界和可靠来源位置是数据合同，不是已实现的格式解析算法。`application.knowledge.indexing.chunk` 中的 `ChunkDraft`、`ChunkGroupDraft` 与 `ChunkingOptions` 同样只是分块策略输入/输出合同。`SourceLocation` 继续位于 `domain.knowledge.value`，位置规则由 `KnowledgeRules.requireLocation` 校验。标题、Token 数、偏移和页码未知时保持 null，不伪造来源位置或估算精确 Token 数。

### 组式保存与向量写入

一个 `ChunkGroupDraft` 可包含一个无父 CONTEXT 和一个或多个 RETRIEVAL，也可只包含一个或多个 RETRIEVAL。组是数据库批次的最小单位：为满足批次目标而保存下一组会超出 `RAG_BATCH_SIZE` 时，先提交已有组；单组自身超过目标时整组独立保存，不拆父子关系。`KnowledgeChunkGroupWriter.saveGroups` 在构建侧为整批组分配连续序号、角色和父序号，转换为现有 `SaveKnowledgeChunkCommand` 后一次调用 `KnowledgeChunkService.saveBatch`；不逐组调用、不拆父子组。

`RAG_MAX_NUM_CHUNKS` 限制 CONTEXT 与 RETRIEVAL 的总片段数。`RAG_BATCH_SIZE` 对数据库是目标组批大小，不拆分组；向量批次则严格受该值约束，尾批可以更小。先提交片段，再为已保存的 RETRIEVAL 构造向量条目并写入；CONTEXT 不进入向量索引。

位于 `infrastructure.knowledge.indexing.vector` 的 Spring AI 向量适配使用相同业务片段 UUID 作为 Spring AI Document ID（32 位 ID 转为标准连字符 UUID，不另造 ID），`knowledge_chunk_id` metadata 保留原业务 ID。metadata 包含知识库、文档、版本、文件、序号、来源名称/MIME、源 SHA-256、角色，以及存在时的父 ID。按 `revision_id` 精确过滤删除。适配器的单元测试使用 VectorStore 替身；不代表已验收真实 Embedding 或 Qdrant 服务。

### 片段及父子关系

基础 `KnowledgeChunkService.saveBatch` 只接受 building 版本，使用 READ_COMMITTED 锁住目标版本，在单事务内校验关系、插入和更新实际片段总数。数据库异常回滚整批；同版本追加串行执行。基础批次输入以版本内全局序号引用父块。构建组只由 `KnowledgeChunkGroupWriter` 转换：为整批组分配连续序号、角色及父序号，映射到现有 `SaveKnowledgeChunkCommand`，通过一次对 Spring 代理 `saveBatch` 的调用持久化；基础服务没有 `saveGroups`，不导入 indexing 合同。

CONTEXT 必须无父；RETRIEVAL 可独立存在或引用同版本的无父 CONTEXT。父块和子块共同占用全局序号，父子正文分别保存；数据库 `chunk_count` 统计所有角色，不等于向量数量。只允许两层上下文关系，不表示完整章节树。具体切分算法和父块生成策略尚未实现。

### 生命周期与失败边界

`KnowledgeBuildLifecycleService.begin` 在 READ_COMMITTED 短事务中锁文档，拒绝已有 building，并以 MAX+1 创建版本；它不改变活动指针。发布时校验版本归属、building 状态、构建开始时的活动快照、所有角色总数及 RETRIEVAL 数量，再原子更新新版本为 ready、源摘要与时间、文档活动版本/indexed，以及旧活动版本为 superseded。失败只允许 building 版本转 failed；首次失败文档变为 failed，重建失败保留旧活动指针和 indexed。

构建对 parser、chunker、向量索引的调用均在数据库事务外。普通失败在成功记录 failed 后尝试按当前版本清理向量，删除成功后才写 `cleaned_at`；删除失败保留原失败阶段/编码并记录固定清理摘要。删除已成功但清理完成写库失败时，只记录固定告警并返回清理未确认，不追加虚假的向量删除失败摘要。失败事实也无法落库时保留向量待核对；发布事务失败同样保留派生向量，不盲目删除或声称提交结果一定已回滚。发布成功后的旧向量删除或清理记录失败不会回滚新活动版本。

没有 MySQL 与 Qdrant 共同事务，也没有完整补偿、幂等治理、building 中断恢复或全面并发协调。当前没有检索实现；未来检索必须先从 MySQL 读取活动版本，再用活动 `revision_id` 过滤向量。ready 状态或向量存在本身不能证明其属于当前可检索版本。

## 字段、位置与时间

领域与应用校验 `heading_path` 有序项、token_count 非负、偏移成对且满足 `0 <= start_offset < end_offset`、页码成对且满足 `1 <= page_start <= page_end`。偏移相对于同一处理后全文的 UTF-16 半开区间，不是原文件字节或 PDF 坐标；无法可靠映射时为空，正文长度不用于限制全文偏移。父子片段各自保存正文和可选来源范围，不根据父范围伪造子位置。

标题 JSON 的 NULL 与空数组分别保留。ID 使用 32 位 UUID；知识库时间通过共享 Clock 取 UTC 微秒，版本时间列使用 `created_at`，其余知识表使用 `create_at`。生产与 H2 schema 见[数据库参考](../reference/database/knowledge.md)。

## 表结构、配置与验证入口

四表和 chunk 字段以[数据库参考](../reference/database/knowledge.md)及 `deploy/mysql/schema.sql` 为准；本次构建骨架不修改 schema。生产脚本手动执行，不升级已有旧表。缺少 `parent_chunk_id`、`chunk_role` 或其索引时，需要另行手动迁移；保留旧片段身份与序号，并将旧平铺数据归为无父 RETRIEVAL。

RAG 配置变量和现有默认值见[配置说明](../reference/configuration.md#rag-构建配置)。没有新增模式变量；模式由内部 `build` 第三个参数选择。普通测试关闭 RAG 外部后端，使用 H2 与策略/向量替身。

| 学习内容 | 代码/测试 |
| --- | --- |
| 基础服务与构建编排 | [knowledge 基础 service](../../src/main/java/com/item/aisummary/application/knowledge/service/)、[indexing/build](../../src/main/java/com/item/aisummary/application/knowledge/indexing/build/)；[KnowledgeIndexBuildServiceTest](../../src/test/java/com/item/aisummary/application/knowledge/indexing/build/KnowledgeIndexBuildServiceTest.java)、[KnowledgeBuildLifecycleServiceIntegrationTest](../../src/test/java/com/item/aisummary/application/knowledge/indexing/build/KnowledgeBuildLifecycleServiceIntegrationTest.java)、[KnowledgeProcessingRouterTest](../../src/test/java/com/item/aisummary/application/knowledge/indexing/build/KnowledgeProcessingRouterTest.java) |
| 源与解析合同 | [indexing/source](../../src/main/java/com/item/aisummary/application/knowledge/indexing/source/)、[indexing/parse](../../src/main/java/com/item/aisummary/application/knowledge/indexing/parse/)；[KnowledgeSourceReaderTest](../../src/test/java/com/item/aisummary/application/knowledge/indexing/source/KnowledgeSourceReaderTest.java)、[FileFormatTest](../../src/test/java/com/item/aisummary/application/knowledge/indexing/source/FileFormatTest.java)、[KnowledgeParseContractsTest](../../src/test/java/com/item/aisummary/application/knowledge/indexing/parse/KnowledgeParseContractsTest.java) |
| 分块合同与组保存 | [indexing/chunk](../../src/main/java/com/item/aisummary/application/knowledge/indexing/chunk/)；[KnowledgeChunkerContractsTest](../../src/test/java/com/item/aisummary/application/knowledge/indexing/chunk/KnowledgeChunkerContractsTest.java)、[KnowledgeChunkGroupWriterTest](../../src/test/java/com/item/aisummary/application/knowledge/indexing/chunk/KnowledgeChunkGroupWriterTest.java)、[KnowledgeChunkServiceTest](../../src/test/java/com/item/aisummary/application/knowledge/service/KnowledgeChunkServiceTest.java) |
| 领域位置规则与 H2 持久化 | [KnowledgeRulesTest](../../src/test/java/com/item/aisummary/domain/knowledge/rule/KnowledgeRulesTest.java)、[SourceLocationTest](../../src/test/java/com/item/aisummary/domain/knowledge/value/SourceLocationTest.java)、[KnowledgePersistenceTest](../../src/test/java/com/item/aisummary/infrastructure/knowledge/persistence/repository/KnowledgePersistenceTest.java) |
| 向量适配器 | [infrastructure `indexing/vector`](../../src/main/java/com/item/aisummary/infrastructure/knowledge/indexing/vector/)；[SpringAiKnowledgeVectorIndexTest](../../src/test/java/com/item/aisummary/infrastructure/knowledge/indexing/vector/SpringAiKnowledgeVectorIndexTest.java) |
| RAG 配置参数 | [infrastructure `indexing/config`](../../src/main/java/com/item/aisummary/infrastructure/knowledge/indexing/config/)；[KnowledgeRagConfigurationTest](../../src/test/java/com/item/aisummary/infrastructure/knowledge/indexing/config/KnowledgeRagConfigurationTest.java)、[KnowledgeRagPropertiesTest](../../src/test/java/com/item/aisummary/infrastructure/knowledge/indexing/config/KnowledgeRagPropertiesTest.java) |

上述测试覆盖固定编排、局部持久化/适配合同和失败行为，不验证真实 parser/chunker、真实文本语义/切分质量、OpenAI/Qdrant 网络调用或生产 MySQL。完整普通测试与外部验收边界见[测试说明](../guides/testing.md)。
