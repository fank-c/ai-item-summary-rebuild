# 知识库基础模块

当前模块提供四表持久化和内部 Java 应用服务。学习目标是理解领域数据、Repository 边界、数据库唯一冲突、JSON 映射和事务批次；这些服务没有使用 Spring AI API，也没有 RAG 处理链路。

返回 [文档导航](../README.md)。字段与约束见 [知识库表结构](../reference/database/knowledge.md)，层次边界见 [工程结构](../architecture.md)，验证命令见 [测试说明](../guides/testing.md)。

## 内部服务能力

| 服务 | 入口 | 行为 |
| --- | --- | --- |
| `KnowledgeBaseService` | `create(name)`、`findById(id)`、`findAll()`、`rename(id,name)` | 多知识库、同名允许；名称 trim 后非空白且最多 100 个 Java 字符。改名只写 name/update_at。 |
| `KnowledgeDocumentService` | `register(actorUserId,knowledgeBaseId,fileId)`、`findById(id)`、`findByKnowledgeBaseId(id)` | 检查知识库存在和源文件归属，只登记元数据。新文档 status=1、pending，活动版本与失败摘要为空。 |
| `KnowledgeRevisionService` | `create(documentId)`、`findById(id)`、`findByDocumentId(id)` | 文档存在后按 MAX + 1 创建 building 版本；从 1 开始，查询按版本号升序。 |
| `KnowledgeChunkService` | `saveBatch(revisionId,commands)`、`findById(id)`、`findByRevisionId(id)` | 向 building 版本追加调用方准备的片段；查询按 sequence_no 升序，保存与实际总数更新共同提交或回滚。 |

详情或关联记录不存在时抛 `KnowledgeNotFoundException`。有效父记录没有子记录时返回空列表。输入错误抛 `IllegalArgumentException`；数据库普通异常继续传播，关键写入要求影响行数符合预期。

这些入口尚未定义 HTTP 合同。没有知识库 Controller、请求路由、分页或公开错误码映射，不应按公开 API 使用。

## 调用与运行方式

运行环境按 [运行指南](../guides/getting-started.md) 准备 MySQL，并手动执行现有正式 schema；应用不连接业务数据库自动建表或迁移旧数据。知识库基础服务没有独立的环境变量。

在已经注入四个服务的 Spring 组件中，可以按以下顺序调用。`actorUserId` 为调用者显式身份，`uploadedFileId` 为已经完成上传的文件 ID：

```java
var base = knowledgeBaseService.create("学习资料");
var document = knowledgeDocumentService.register(actorUserId, base.id(), uploadedFileId);
var revision = knowledgeRevisionService.create(document.id());
var saved = knowledgeChunkService.saveBatch(revision.id(), List.of(
    new SaveKnowledgeChunkCommand(0, preparedContent, null, null, null, null, null, null)
));
var orderedChunks = knowledgeChunkService.findByRevisionId(revision.id());
```

`preparedContent` 必须由调用方准备。这段调用只登记与保存数据，不读取文件正文、不解析、不切片、不发布，也不会让 pending 文档变为 indexed。

## 登记与权限边界

登记显式接收 `actorUserId`，保持原样，不读取 HTTP 上下文或伪造默认用户。缺少身份仍抛 `AuthenticationRequiredException`，超过 64 个 Unicode 码点由现有身份规则拒绝。

服务确认知识库存在后调用 `FileContentService.requireOwnedProcessable(actorUserId,fileId)`。该合同查询文件元数据，检查未删除、status=1、completed 和所有者匹配。归属或状态不符继续使用文件模块的未找到异常；服务不调用文件 Mapper，也不打开内容流。

知识库不保存 owner_user_id，允许创建多条记录及同名库。文件归属检查不等于知识库访问授权。本阶段没有知识库权限管理，内部调用方应在后续对外暴露前接入独立授权能力；缺少所有者字段不能解释为公开访问。

同一知识库登记同一文件返回 `DOCUMENT_ALREADY_REGISTERED`。服务先检查登记关系，数据库联合唯一索引兜底。不同知识库可以登记同一可处理文件。

## 版本与片段事务

版本创建只分配 building 记录。`created_at` 与 `started_at` 是创建并进入 building 的时间；`source_sha256`、失败字段、`completed_at`、`published_at` 和 `cleaned_at` 均为空，chunk_count=0。服务不读取文件，也不模拟构建成功。

同一文档内版本号递增。两个并发调用可以读到相同最大值；数据库唯一约束使其中一个失败，返回 `REVISION_NUMBER_CONFLICT`。版本号达到 Integer.MAX_VALUE 时返回 `REVISION_NUMBER_EXHAUSTED`。没有分布式锁、静默覆盖或自动重试。

片段输入 `application.knowledge.command.SaveKnowledgeChunkCommand` 包含序号、正文、标题路径、Token 数、偏移和页码，不接受片段 ID 或 revisionId。所有片段由本次 `revisionId` 入口确定归属，生成独立 32 位 UUID，防止批次混入其他版本。

批次非空，正文非空白，序号非负且批内唯一。已存序号冲突返回 `CHUNK_SEQUENCE_CONFLICT`；ready、failed、superseded 版本拒绝写入，返回 `REVISION_NOT_BUILDING`。服务没有修改既有片段正文的入口。

`saveBatch` 使用 Spring 事务与 READ_COMMITTED 隔离级别。先验证完整批次，再锁住目标版本记录，确认 building，逐条插入并计算该版本数据库中的实际总数，最后更新 chunk_count。该计数包括之前成功追加的批次，不只是当前输入数量。

版本行锁保持到事务结束，同版本批次串行执行。任意插入失败、唯一冲突或计数更新失败都会回滚当前批次及计数；旧批次保留。加入外层事务时，基础设施检查实际连接的隔离级别；不兼容的事务在写入前明确失败，兼容的外层事务回滚也会回滚当前批次。必须通过 Spring 注入的 `KnowledgeChunkService` 调用此入口，事务代理不作用于自行 `new` 出的服务。

## 字段、位置与时间

- `heading_path` 为有序 `List<Heading>`；标题层级为正、标题非空白。JSON NULL 与空数组分别保留，中文、引号、反斜杠与换行均按 JSON 编码往返。列表在领域对象中复制为不可修改的快照。
- 使用工程已有 Jackson 3。MySQL 绑定 JSON 文本；H2 的 JSON 字符串绑定会把数组变成 JSON 字符串，因此测试数据库使用原生 UTF-8 JSON 字节绑定。H2 测试不证明真实 MySQL 驱动行为。
- `token_count` 可以为空，有值时非负，仅保存调用方提供的数量，不宣称是模型实际 Token 数。
- 偏移同时为空或满足 `0 <= start_offset < end_offset`。它们相对处理后的完整文本，不用当前片段正文长度限制上限。
- 页码同时为空或满足 `1 <= page_start <= page_end`。未知位置保持为空，不推算或伪造；当前四表不保存处理后全文、OCR 坐标或 Word 段落位置。
- 应用生成的业务 ID 沿用无连字符 32 位 UUID。时间使用工程 Clock、Instant 和 UTC 微秒。Revision 对应 `created_at`，其余三表对应 `create_at`；所有时间复用现有 UTC 类型处理器。

## 测试与学习入口

| 内容 | 源码入口 |
| --- | --- |
| 四个用例服务 | [application.knowledge.service](../../src/main/java/com/item/aisummary/application/knowledge/service/) |
| 片段批次输入合同 | [SaveKnowledgeChunkCommand](../../src/main/java/com/item/aisummary/application/knowledge/command/SaveKnowledgeChunkCommand.java) |
| 四个仓储接口 | [application.knowledge.port](../../src/main/java/com/item/aisummary/application/knowledge/port/) |
| 四表领域记录 | [domain.knowledge.model](../../src/main/java/com/item/aisummary/domain/knowledge/model/) |
| 标题、状态、规则和异常 | [value](../../src/main/java/com/item/aisummary/domain/knowledge/value/)、[enums](../../src/main/java/com/item/aisummary/domain/knowledge/enums/)、[rule](../../src/main/java/com/item/aisummary/domain/knowledge/rule/)、[exception](../../src/main/java/com/item/aisummary/domain/knowledge/exception/) |
| Spring 装配 | [KnowledgeConfiguration](../../src/main/java/com/item/aisummary/infrastructure/knowledge/config/KnowledgeConfiguration.java) |
| 数据库实体、SQL、仓储适配和 JSON 转换 | [entity](../../src/main/java/com/item/aisummary/infrastructure/knowledge/persistence/entity/)、[mapper](../../src/main/java/com/item/aisummary/infrastructure/knowledge/persistence/mapper/)、[repository](../../src/main/java/com/item/aisummary/infrastructure/knowledge/persistence/repository/)、[typehandler](../../src/main/java/com/item/aisummary/infrastructure/knowledge/persistence/typehandler/) |
| 非法正文、序号、Token、位置和标题快照 | [KnowledgeRulesTest](../../src/test/java/com/item/aisummary/domain/knowledge/rule/KnowledgeRulesTest.java) |
| 四表真实字段映射、JSON 与唯一约束 | [KnowledgePersistenceTest](../../src/test/java/com/item/aisummary/infrastructure/knowledge/persistence/repository/KnowledgePersistenceTest.java) |
| 多库同名、文件归属、版本递增与竞争 | [KnowledgeServicesTest](../../src/test/java/com/item/aisummary/application/knowledge/service/KnowledgeServicesTest.java) |
| 追加、排序、计数、写入失败与计数失败回滚 | [KnowledgeChunkServiceTest](../../src/test/java/com/item/aisummary/application/knowledge/service/KnowledgeChunkServiceTest.java) |

普通测试只使用隔离 H2 和真实文件元数据服务，模型与两个对象存储均关闭。失败回滚测试没有测试外层事务，另外验证服务加入外层事务的兼容性与共同回滚；数据库 CHECK 约束只作为 H2 中的失败注入，不修改生产表结构。定向与完整验证命令见 [测试说明](../guides/testing.md#定向运行)。

当前没有知识库真实 MySQL 专项验收，既有 mysql-it profile 只覆盖用户。当前同样没有 HTTP 管理、删除、级联清理、构建任务、版本发布、权限、文件解析、OCR、Embedding、Qdrant、检索或问答。
