# 知识库表结构

知识库基础使用 `knowledge_base`、`knowledge_document`、`knowledge_document_revision`、`knowledge_chunk` 四张表，支持基础内部服务和 txt/md 同步 RAG 入库、版本发布。当前没有知识库 HTTP、权限、检索或问答。

返回 [文档导航](../../README.md)。内部调用合同见 [知识库模块](../../modules/knowledge.md)，初始化步骤见 [运行指南](../../guides/getting-started.md)，验证方式见 [测试说明](../../guides/testing.md)。

正式定义以 [schema.sql](../../../deploy/mysql/schema.sql) 为准，H2 定义见 [测试 schema](../../../src/test/resources/schema.sql)。两者同步维护字段、可空性、主键、唯一约束和普通索引。MySQL 使用 `DATETIME(6)`，H2 使用 `TIMESTAMP(6)`；时间按 UTC 解释。

## 数据关系

```text
knowledge_base
  └── knowledge_document ── file_id → file_asset
        ├── knowledge_document_revision
        │     └── knowledge_chunk
        └── active_revision_id → 当前可查询的完整版本（可空）
```

- 知识库只保存自身信息，不记录用户归属，允许多库同名。
- 知识文档表示文件与知识库的登记关系，同一知识库不能重复登记同一文件。
- 一个文档可以有多个版本，一个版本可以有多个片段。片段保存完整分块结果，区分上下文块（CONTEXT）与检索块（RETRIEVAL）；检索块可关联同版本的上下文父块，不表示完整章节树。
- 片段 ID 是 32 位业务 UUID，Qdrant 使用同一个 UUID 的标准连字符表示；metadata.knowledge_chunk_id 保留业务原值，不新增映射表。

表间采用逻辑关联，没有物理外键或级联删除。应用服务检查知识库、文档和版本是否存在，并通过文件模块检查源文件归属。发布事务确认目标版本属于同一文档、处于 building、实际计数完整且活动快照未变化，然后原子更新 ready、活动指针和旧版本 superseded。Qdrant 操作不属于该数据库事务。

## 字段定义

以下类型为正式 MySQL 类型。“可空”描述数据库约束；非空白、位置范围等额外规则由领域和应用服务校验。

### knowledge_base

| 字段 | 类型 | 可空 | 含义与约束 |
| --- | --- | --- | --- |
| `id` | VARCHAR(32) | 否 | 知识库 ID，主键 |
| `name` | VARCHAR(100) | 否 | 展示名称；服务 trim 后非空白且最多 100 个 Java 字符，允许同名 |
| `status` | INT | 否 | 可用状态，默认 1 |
| `create_at`、`update_at` | DATETIME(6) | 否 | 创建与修改时间 |

表中没有 `owner_user_id`，也没有每个用户只能拥有一个知识库的限制。访问关系需要后续独立权限能力处理；缺少所有者字段不能解释为所有用户均可访问，源文件归属也不能代替知识库授权。

### knowledge_document

| 字段 | 类型 | 可空 | 含义与约束 |
| --- | --- | --- | --- |
| `id` | VARCHAR(32) | 否 | 知识文档 ID，主键 |
| `knowledge_base_id`、`file_id` | VARCHAR(32) | 否 | 所属知识库和源文件；联合唯一 |
| `status` | INT | 否 | 文档可用状态，默认 1 |
| `index_status` | VARCHAR(16) | 否 | pending、indexed、failed、removed；登记时为 pending，不表示后台任务进度 |
| `active_revision_id` | VARCHAR(32) | 是 | 当前可查询的完整版本；尚无成功版本时为空 |
| `last_failure_code` | VARCHAR(64) | 是 | 最近一次构建失败编码 |
| `last_failure_message` | VARCHAR(512) | 是 | 最近一次构建失败的脱敏摘要 |
| `create_at`、`update_at` | DATETIME(6) | 否 | 创建与修改时间 |

当前登记创建 pending 文档，活动版本与失败摘要为空。基础创建版本和保存片段不更新这些文档结果字段。完整构建成功才发布，失败保留旧的可用活动版本；仍有旧可用版本时保持 indexed，最近失败摘要单独记录。没有旧活动版本时构建失败使文档变为 failed。

### knowledge_document_revision

| 字段 | 类型 | 可空 | 含义与约束 |
| --- | --- | --- | --- |
| `id` | VARCHAR(32) | 否 | 版本 ID，主键 |
| `knowledge_document_id` | VARCHAR(32) | 否 | 所属文档 ID |
| `revision_number` | INT | 否 | 文档内递增版本号，与文档 ID 联合唯一；服务从 1 开始分配 |
| `revision_status` | VARCHAR(24) | 否 | building、ready、failed、superseded；表示版本整体状态 |
| `source_sha256` | CHAR(64) | 是 | 实际读取源文件的 SHA-256；当前创建版本时为空 |
| `chunk_count` | INT | 否 | 本版本全部片段数量（含 CONTEXT 与 RETRIEVAL），默认 0；追加后更新为实际总行数，不等于向量数量 |
| `failure_stage`、`failure_code` | VARCHAR(64) | 是 | 失败发生的阶段与编码 |
| `failure_message` | VARCHAR(512) | 是 | 失败的脱敏摘要 |
| `created_at`、`started_at` | DATETIME(6) | 否 | 创建和进入 building 的时间 |
| `completed_at`、`published_at` | DATETIME(6) | 是 | 完成和发布时间 |
| `cleaned_at` | DATETIME(6) | 是 | 本阶段为向量清理完成时间；失败版本及数据库片段保留 |

基础版本创建只分配 building 记录。完整构建服务另在文档锁下检查 building 互斥并执行 Reader、切分与向量写入。创建时完成、发布、清理时间和失败信息为空；成功发布写 source_sha256、completed_at、published_at，ready 与文档 active_revision_id 共同表示活动版本，旧活动版本变为 superseded。失败写固定阶段、编码、摘要和结束时间，但不填 published_at。

版本表保存整体结果、失败摘要、源文件摘要、数量和时间，不保存解析策略、切片策略及参数、规范化策略、质量状态、向量路由或阶段进度 JSON。片段表也不保存切片策略，因此无法从历史版本还原当时的处理参数。后续更换向量模型或集合时，应另行判断兼容性和索引重建需求，不能假定不同模型的向量可混用。

没有独立的清理状态字段。外部向量删除成功后才写 cleaned_at，MySQL 片段仍保留。清理失败记录固定摘要且 cleaned_at 为空；该时间为空单独不能区分未清理、无需清理或失败。

### knowledge_chunk

| 字段 | 类型 | 可空 | 含义与约束 |
| --- | --- | --- | --- |
| `id` | VARCHAR(32) | 否 | 片段 ID，主键 |
| `revision_id` | VARCHAR(32) | 否 | 所属版本 |
| `parent_chunk_id` | VARCHAR(32) | 是 | 同版本的 CONTEXT 父块 ID；无父块为 NULL，不使用空串 |
| `chunk_role` | VARCHAR(16) | 否 | CONTEXT 或 RETRIEVAL，大小写固定；无数据库默认值，由保存入口明确提供 |
| `sequence_no` | INT | 否 | 版本内非负全局序号，父块与检索块共同占用，与版本 ID 联合唯一；不是兄弟节点序号或原文位置，基础入口不要求连续 |
| `content` | MEDIUMTEXT | 否 | 正文；服务要求非空白，保存时保留原样 |
| `heading_path` | JSON | 是 | 有序标题路径，例如 `[{"level":1,"title":"说明"}]` |
| `token_count` | INT | 是 | 调用方提供的分词数量；有值时非负 |
| `start_offset`、`end_offset` | INT | 是 | 处理后全文中的 UTF-16 半开区间，结束位置不包含在内 |
| `page_start`、`page_end` | INT | 是 | 来源页码，起始页从 1 开始，结束页包含在范围内 |
| `create_at` | DATETIME(6) | 否 | 创建时间 |

角色与关系组合固定为：CONTEXT 无父块；RETRIEVAL 可独立存在，或指向同版本的无父 CONTEXT 块。只支持两层上下文关系，禁止自引用、缺失父块、跨版本引用及以 RETRIEVAL 为父块。不存在独立的更新父子关系接口，重新切分应生成新版本；沿用逻辑关联、不加物理外键或 SQL CHECK，由服务校验全部关系后插入。

父块和检索块分别保存正文，允许内容重叠，不以拼接子块代替父块正文。`chunk_role` 表示用途，不是 TEXT/TABLE/CODE 内容类别；本次仅提供存储与关系合同，没有新增父子切分器、向量写入或父块检索流程。后续索引按角色选择 RETRIEVAL，不能默认所有片段都向量化。

标题按原顺序保存，层级为正、标题非空白。标题路径表示来源章节，与上下文父子关系独立；父子可具有相同路径。JSON NULL 和空数组分别还原，不把 JSON 数组保存为字符串；MySQL 与 H2 的绑定方式见 [知识库模块](../../modules/knowledge.md#字段位置与时间)。

偏移必须同时为空或满足 `0 <= start_offset < end_offset`；页码必须同时为空或满足 `1 <= page_start <= page_end`。这些规则由领域及内部服务校验，正式 schema 不增加 SQL CHECK 约束。父子偏移使用同一处理后全文参照，不改成相对父块的位置，重叠检索块的区间可以重叠；无法可靠映射到连续区间时为空。全文偏移不受当前片段正文长度限制；Token 数是本行正文的计数，不累计子块，也不保证等于未来 Embedding 模型的实际 Token 数。

txt/md 自动构建的 heading_path、token_count、偏移与页码全部为空。基础片段入口仍保存调用方提供的合法值。未知位置保持为空，不从父章节复制范围后宣称是子片段的精确范围。Spring AI 继承 metadata 不会自动生成可靠的原文偏移。当前偏移不表示原文件字节位置或 PDF 坐标，四表也未保存用于定位的完整处理后文本、OCR 区域或 Word 段落位置。需要原文高亮时，应先明确参考文本保存方式和解析器的位置输出。

## 索引与写入约束

四张表均以 `id` 为主键，另有以下索引：

| 表 | 索引 | 类型与字段 |
| --- | --- | --- |
| `knowledge_document` | `uk_knowledge_document_base_file` | 唯一：`knowledge_base_id, file_id` |
| `knowledge_document` | `idx_knowledge_document_file` | 普通：`file_id` |
| `knowledge_document_revision` | `uk_knowledge_revision_document_number` | 唯一：`knowledge_document_id, revision_number` |
| `knowledge_document_revision` | `idx_knowledge_revision_document_status` | 普通：`knowledge_document_id, revision_status` |
| `knowledge_chunk` | `uk_knowledge_chunk_revision_sequence` | 唯一：`revision_id, sequence_no` |
| `knowledge_chunk` | `idx_knowledge_chunk_revision_parent_sequence` | 普通：`revision_id, parent_chunk_id, sequence_no`；支持同版本按父块顺序读取子块 |

文档登记、版本分配和片段追加均由唯一索引兜底。版本号采用 MAX + 1，竞争时明确报冲突，不自动重试；达到 int 上限时拒绝继续分配。片段只允许追加到 building 版本，插入和总数更新在同一个 READ_COMMITTED 事务中，锁定目标版本以串行执行同版本批次，失败整批回滚。父引用通过命令中的同版本 `parentSequenceNo` 指定，服务为本批统一分配业务 ID，在版本锁内解析本批或已存父块；不接受调用方传入 chunk ID 或 revisionId 覆盖归属。具体异常与外层事务要求见 [知识库模块](../../modules/knowledge.md#片段与向量批次)。

## 初始化与验证边界

应用不自动建表。在明确选定的 MySQL 数据库中手动执行 `SOURCE deploy/mysql/schema.sql;`。`CREATE TABLE IF NOT EXISTS` 只创建缺失表，不会删除旧列、调整可空性或升级已有表。

旧表缺少 `parent_chunk_id`、`chunk_role` 或新增索引时，仅执行本脚本不能升级。需另行评估并手动升级；既有平铺片段应保留 ID、版本与序号，回填 `parent_chunk_id=NULL`、`chunk_role=RETRIEVAL` 后再施加非空约束。本次不连接或修改已有数据库。

旧库升级与历史数据导入需要单独处理。导入时应核实 DATETIME 原时区，并保持文档、版本、片段及向量的对应关系；采用新的解析或切片规则时，应安排索引重建，不能直接沿用旧实体和 Mapper。

四表真实 Mapper 测试覆盖字段、两种片段角色与父引用、按版本和序号查找、唯一索引、JSON 数组顺序、可空位置和 UTC 微秒。内部服务测试覆盖关联与文件归属、版本竞争，以及普通块兼容、同批和既存父块解析、非法关系拒绝、片段排序、两种角色总数、并发追加和失败回滚。测试源码入口见 [知识库模块](../../modules/knowledge.md#测试与学习入口)。

普通测试使用隔离 H2，覆盖构建和发布的本地行为；不能替代正式 MySQL、OpenAI 或 Qdrant 验收，也不能证明尚未实现的位置生成或检索可用。既有 `mysql-it` profile 只覆盖用户模块。
