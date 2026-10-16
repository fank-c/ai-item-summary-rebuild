# 知识库模块

当前提供知识库四表持久化、内部基础服务、本地 TXT/Markdown/DOCX 生产文档解析器、基于阿里云文档智能的可选 PDF 云端解析器、TokenBudgetKnowledgeChunker（支持 FLAT 与 PARENT_CHILD 模式），以及固定 RAG 构建编排与版本生命周期。默认仍保持 RAG 关闭，缺少 OpenAI Embedding 或 Qdrant 等外部后端依赖时显式开启 RAG 仍拒绝启动；解析与切分实现不代表真实外部服务已验收或生产端到端完成。

返回 [文档导航](../README.md)。启动与测试命令见 [项目 README](../../README.md)，运行变量见 [配置说明](../reference/configuration.md#rag-构建配置)，表结构见 [生产 SQL](../../deploy/mysql/schema.sql)。

## 学习目标与范围

本模块用于学习内部知识库服务、chunk 父子数据合同、Spring 事务下的版本生命周期，以及应用如何通过 parser/chunker/vector ports 组织可替换处理步骤。Spring AI 向量适配器把已保存的 RETRIEVAL 片段转换为向量文档。

解析器统一输出 `ParsedDocument`，切分器支持 FLAT 与 PARENT_CHILD。Markdown 直接使用 CommonMark 并保留源文与位置，不使用会重新拼接正文的 reader；Token 预算使用本地 JTokkit CL100K_BASE。依赖版本以 [pom.xml](../../pom.xml) 为准。

没有知识库 HTTP、权限管理、删除、异步任务、检索、聊天或重排序。文件归属检查不等于知识库授权。MySQL 保存权威版本状态；向量索引是外部派生数据，两者没有共同事务。

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
| `KnowledgeDocumentParser` / `KnowledgeChunker` / `KnowledgeVectorIndex` | 按 parse/chunk/vector 能力组织的应用层扩展合同；解析器已提供 TXT、Markdown、DOCX 本地生产实现与可选 DocMind PDF 云端实现，切分策略由 TokenBudgetKnowledgeChunker 提供 FLAT 与 PARENT_CHILD 两种生产模式 |

构建入口和组 writer 仅在 RAG 开启时装配。`KnowledgeParserConfiguration`、`KnowledgeChunkerConfiguration` 分别注册本地解析器和切分器；`DocMindParserConfiguration` 还要求显式开启云解析；`KnowledgeRagConfiguration` 检查外部后端与策略依赖，缺失时拒绝启动。开关及条件见 [配置说明](../reference/configuration.md#rag-构建配置)。

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

`application.knowledge.indexing.source` 中的 `KnowledgeSourceReader` 将源流读至受控内存，校验记录大小、实际大小上限和原字节 SHA-256，生成可重复打开的 `PreparedSource`。资源流由调用者通过 try-with-resources 关闭。此步骤只准备和校验字节，不做格式解析。TXT/Markdown 委托 `Utf8TextDecoder` 严格解码并规整换行；DOCX 先做 ZIP/XML 预检；PDF 先经本地 PDFBox 校验，再提交 DocMind。

`application.knowledge.indexing.parse` 中的 `ParsedDocument` 与有序 `ContentBlock` 表达 parser 的结构化输出；内容类别、标题路径、边界和可靠来源位置是格式无关的数据合同；各格式解析算法留在基础设施层。`application.knowledge.indexing.chunk` 中的 `ChunkDraft`、`ChunkGroupDraft` 与 `ChunkingOptions` 同样只是分块策略输入/输出合同。`SourceLocation` 继续位于 `domain.knowledge.value`，位置规则由 `KnowledgeRules.requireLocation` 校验。标题、Token 数、偏移和页码未知时保持 null，不伪造来源位置或估算精确 Token 数。

### 组式保存与向量写入

一个 `ChunkGroupDraft` 可包含一个无父 CONTEXT 和一个或多个 RETRIEVAL，也可只包含一个或多个 RETRIEVAL。组是数据库批次的最小单位：为满足批次目标而保存下一组会超出 `RAG_BATCH_SIZE` 时，先提交已有组；单组自身超过目标时整组独立保存，不拆父子关系。`KnowledgeChunkGroupWriter.saveGroups` 在构建侧为整批组分配连续序号、角色和父序号，转换为现有 `SaveKnowledgeChunkCommand` 后一次调用 `KnowledgeChunkService.saveBatch`；不逐组调用、不拆父子组。

`RAG_MAX_NUM_CHUNKS` 限制 CONTEXT 与 RETRIEVAL 的总片段数。`RAG_BATCH_SIZE` 对数据库是目标组批大小，不拆分组；向量批次则严格受该值约束，尾批可以更小。先提交片段，再为已保存的 RETRIEVAL 构造向量条目并写入；CONTEXT 不进入向量索引。

位于 `infrastructure.knowledge.indexing.vector` 的 Spring AI 向量适配使用相同业务片段 UUID 作为 Spring AI Document ID（32 位 ID 转为标准连字符 UUID，不另造 ID），`knowledge_chunk_id` metadata 保留原业务 ID。metadata 包含知识库、文档、版本、文件、序号、来源名称/MIME、源 SHA-256、角色，以及存在时的父 ID。按 `revision_id` 精确过滤删除。适配器的单元测试使用 VectorStore 替身；不代表已验收真实 Embedding 或 Qdrant 服务。

### 片段及父子关系

基础 `KnowledgeChunkService.saveBatch` 只接受 building 版本，使用 READ_COMMITTED 锁住目标版本，在单事务内校验关系、插入和更新实际片段总数。数据库异常回滚整批；同版本追加串行执行。基础批次输入以版本内全局序号引用父块。构建组只由 `KnowledgeChunkGroupWriter` 转换：为整批组分配连续序号、角色及父序号，映射到现有 `SaveKnowledgeChunkCommand`，通过一次对 Spring 代理 `saveBatch` 的调用持久化；基础服务没有 `saveGroups`，不导入 indexing 合同。

CONTEXT 必须无父；RETRIEVAL 可独立存在或引用同版本的无父 CONTEXT。父块和子块共同占用全局序号，父子正文分别保存；数据库 `chunk_count` 统计所有角色，不等于向量数量。只允许两层上下文关系，不表示完整章节树。在 PARENT_CHILD 模式下，父块作为 CONTEXT 保存入库但不写向量，子块作为 RETRIEVAL 关联父块并写入向量索引；若短内容单一 child 块已覆盖整段 parent，则不额外存一份相同父正文（context = null，直接为无父检索块）。

### 生命周期与失败边界

`KnowledgeBuildLifecycleService.begin` 在 READ_COMMITTED 短事务中锁文档，拒绝已有 building，并以 MAX+1 创建版本；它不改变活动指针。发布时校验版本归属、building 状态、构建开始时的活动快照、所有角色总数及 RETRIEVAL 数量，再原子更新新版本为 ready、源摘要与时间、文档活动版本/indexed，以及旧活动版本为 superseded。失败只允许 building 版本转 failed；首次失败文档变为 failed，重建失败保留旧活动指针和 indexed。

构建对 parser、chunker、向量索引的调用均在数据库事务外。普通失败在成功记录 failed 后尝试按当前版本清理向量，删除成功后才写 `cleaned_at`；删除失败保留原失败阶段/编码并记录固定清理摘要。删除已成功但清理完成写库失败时，只记录固定告警并返回清理未确认，不追加虚假的向量删除失败摘要。失败事实也无法落库时保留向量待核对；发布事务失败同样保留派生向量，不盲目删除或声称提交结果一定已回滚。发布成功后的旧向量删除或清理记录失败不会回滚新活动版本。

没有 MySQL 与 Qdrant 共同事务，也没有完整补偿、幂等治理、building 中断恢复或全面并发协调。当前没有检索实现；未来检索必须先从 MySQL 读取活动版本，再用活动 `revision_id` 过滤向量。ready 状态或向量存在本身不能证明其属于当前可检索版本。

## 文档解析规则与安全预算

### 严格解码与受控预处理（`Utf8TextDecoder`）

解析实现位于 `infrastructure.knowledge.indexing.parse`，按 `txt`、`markdown`、`docx`、`pdf` 分包。TXT 与 Markdown 解析器均委托 `support.Utf8TextDecoder` 执行统一的受控字节解码；该支持类及解码入口公开，供两个格式子包复用：

- **严格 UTF-8 校验**：使用 `CharsetDecoder`（`REPORT` 模式），拒绝非法或截断的 UTF-8，并拒绝 NUL 字符。不自动猜测或转换 GBK/UTF-16；不同编码可能共享合法字节，因此不声称能识别所有源编码。失败异常使用固定安全文案，不回显源正文或文件名。
- **BOM 与换行符统一**：若文本开头包含至多一个 UTF-8 BOM（`\uFEFF`）则予以剥离；正文所有换行符（CRLF、CR）统一规范化为 LF（`\n`），正文其余字符原样保留。
- **内存流管理与预算检查**：通过 `InputStreamReader` 增量解码已准备的内存流，避免再次复制整份原字节；使用 `try-with-resources` 关闭流。校验源格式匹配及元数据/读取字节预算，源大小不得超过 `maxSourceSize`。

### TXT 解析规则（`TextKnowledgeDocumentParser`）

- **自然段切分**：由包含空白字符（涵盖普通空白与 `Character.isSpaceChar` 如 NBSP）的连续空行作为自然段落分隔符。
- **正文保留**：段内换行与段内空格缩进原样保留，不执行 trim；段外用于分隔的换行不计入内容块。
- **内容块规格**：全部内容块类别均为 `ContentBlockType.TEXT`，`headingPath = null`，页码保持为 null，`boundaryBefore = false`，序号从 0 连续递增。
- **来源位置**：`SourceLocation` 偏移为规整后全文（统一 LF）的 UTF-16 substring 半开区间 `[start_offset, end_offset)`。
- **失败边界**：空白文档（含纯空白、纯 NBSP 或 0 字节）明确解析失败抛出异常。

### Markdown 解析规则（`MarkdownKnowledgeDocumentParser`）

- **引擎与顶层块提取**：基于 CommonMark 规范与 GFM 表格扩展（`org.commonmark:commonmark` 与 `org.commonmark:commonmark-ext-gfm-tables:0.22.0`），只识别顶层块（top-level blocks），保留原 text。
- **原文保留与位置映射**：TXT/MD 当前输出 source 均为规整后全文的可靠 UTF-16 substring 半开区间 `[start_offset, end_offset)`。Markdown 通过 node spans 换算为行起始的 UTF-16 字符偏移（非字节偏移），使用 block 级 spans 截取原文 substring，不使用 renderer 回写生成（避免丢失位置和破坏原文）；行内 AST 采用轻量 parser 留 literal TEXT，避免生成大量 inline AST，行内语法留在正文。
- **各类型块处理规则**：
  - `HEADING`：包含 `#` 或 Setext 形式的完整原文；headingPath 提取标题名去掉结构符号（如 `#`、下划线），但保留 inline 语法；heading 每次均作为硬边界（`boundaryBefore = true`）。CommonMark 允许的空标题保留为 `TEXT`，移除同级及更深标题路径，不构造空白的领域标题。
  - 水平线（THEMATIC_BREAK）：不输出为单独 ContentBlock，但作为其后紧邻下一 block 的硬边界（`boundaryBefore = true`）。
  - `CODE`：包含围栏（``` / ~~~）或缩进，保留完整源码。
  - `TABLE`：保留完整表格源码结构。
  - 列表与引用（LIST / BLOCK_QUOTE）：整个列表或块引用作为单个 `TEXT` 块输出，不递归重复输出嵌套段落，不用嵌套标题污染外层 headingPath。
  - HTML、图片、公式等均保留原文，不执行远程资源下载或 OCR。
  - Reference 定义（Link Reference Definition）保留为 `TEXT`。
  - 容错与失败边界：不完整/畸形 markdown 按 CommonMark 规范容错解释；全空白或仅包含水平线时解析失败；node 无 spans（`spans.isEmpty()` 或行索引越界）时明确解析失败抛出异常，不估算位置（扩展 port 其他格式时位置允许为 null）；未知页码为 null。

### DOCX 解析规则（`DocxKnowledgeDocumentParser`）

- **引擎与顺序保障**：基于 Apache POI 5.4.1，仅支持 `.docx`。严格保留顶层段落（paragraph）与表格（table）的交错阅读顺序（`document.getBodyElements()`），拒绝乱序。
- **正文提取与规整**：合并段落 runs 提取正文，过滤修订删除文本（`delText`），统一换行为 LF（`\n`）；超链接仅提取显示文本，不发起外部网络访问；页眉/页脚/批注/脚注/尾注不提取，不做图片 OCR，Word 页码为 null。
- **大纲标题判定（`DocxHeadingResolver`）**：
  - 优先级 1：段落显式大纲层级优先（`pPr/outlineLvl`），0..8 映射为 1..9 级标题；9 为显式正文，阻断后续样式回退；
  - 优先级 2：未显式声明时沿 `basedOn` 样式继承链回溯，匹配样式的 `outlineLvl` 或中英文已知标题命名（如 `Heading 1..9`、`标题 1..9`）；
  - 防御约束：最大样式继承深度 32，严格检测并拦截循环继承；不根据字体字号猜测标题；
  - 标题块标记 `boundaryBefore = true`，标题路径快照不可变；遇到首个标题前路径为 `[]`，区别于 TXT/PDF 的未知标题路径 `null`。
- **表格提取**：表格列按 Tab（`\t`）分隔、行按 LF（`\n`）分隔，空单元格保留；单元格内多段落按 LF 连接；嵌套表格及不支持的正文容器（如 SDT、altChunk）明确拒绝。
- **位置与前置安全防御（`DocxPreflightChecker`）**：
  - 以固定 `\n\n` 拼接全部块计算 UTF-16 半开区间 `[start_offset, end_offset)`；
  - POI 实例化前通过 `DocxPreflightChecker` 预检 ZIP 预算（1,000 entries、单条目 50MiB、累计 100MiB、解压比 100:1、ZipSlip 防护、拒绝 XML DTD/XXE）与 StAX 结构深度/数量/字符防御（嵌套深度 128、100,000 blocks、50,000,000 chars）。

### PDF 解析与云端网关

- **混合架构设计**：基于阿里云文档智能（`docmind_api20220711:2.0.14`）结合 Apache PDFBox 3.0.5 本地校验。
- **本地 PDFBox 容器防线**：PDFBox 仅在本地用于验证 PDF 容器完整性、拦截损坏/加密文件，并限制总物理页数不超过 2,000 页（`MAX_PAGES`），本地不执行文本提取，避免未授权或超长扫描文件产生云端调用。
- **云端文档智能分析与 OCR**：
  - 页面正文提取、扫描件 OCR 与版面分析委托阿里云文档智能（支持常规扫描教材、说明书等，当前不要求手写识别）；
  - 版面严格按 `layout.index` 升序排序（非负唯一，保证阅读顺序）；
  - DocMind 官方 0 基 `pageNum` 映射为系统 1 基物理页码 `pageNum + 1`；
  - 严格比对 `pageImage` 影像清单覆盖全部本地物理页数（接受完整的 0 基或 1 基清单）；缺页、重复或非法页码拒绝构建。允许清单中的空白页没有版面，但全篇无内容或任一版面无法表示为非空白正文时失败；
  - 标题层级策略：DocMind 的 `layout.level` 仅表示版面嵌套深度，不能作为可信文档大纲，所有内容块的 `headingPath` 均为 `null`；标题块标记为 `HEADING`，标题及跨页均标记硬边界（`boundaryBefore = true`）；
  - 表格依次取非空的 `llmResult`、`markdownContent`、`text`，直接保留云端返回的 HTML/Markdown，不重新渲染或保证回退文本仍有合并单元格信息；图示有 `llmResult` 时优先保留说明文字。
- **云端客户端（`DocMindPdfParserClient`）**：
  - 通过 `SubmitDocParserJobAdvanceRequest` 提交内存流、轮询状态并在成功后以步长 100 分页拉取版面；
  - 禁用 SDK 自动重试；`ALIYUN_DOCMIND_WAIT_TIMEOUT` 覆盖提交、轮询和结果拉取。单次连接最多 5s、读取最多 30s，并随截止时间剩余预算收缩；同步 SDK 无法保证硬实时取消或撤回远端任务，超时和进程重启不恢复任务，重新提交可能重复计费；
  - 异常统一脱敏为固定中文错误，丢弃底层包含密钥或远端响应的 cause，防止凭据泄露。

### 固定安全预算与条件装配

- **固定安全预算与防御边界**（硬编码常量，不新增配置变量）：
  - 内容块数量上限：各解析器输出内容块最大限制为 100,000（`MAX_BLOCKS`）；
  - Markdown 最大解析行数：1,000,000 行；Markdown 容器嵌套保护阈值：128；
  - DOCX / PDF 提取总字符数上限：50,000,000 字符；DOCX ZIP 解压比上限 100:1、条目数上限 1,000、解压总大小上限 100MiB；PDF 最大物理页数上限 2,000 页；
  - 固定 guards 为硬编码解析防御限制，不是安全保证（不作为恶意 DoS 防护承诺）；超限明确拒绝，不截断内容。以上预算独立于 `RAG_MAX_NUM_CHUNKS`。
- **条件装配与配置复用**：
  - `KnowledgeParserConfiguration` 在 `app.knowledge.rag.enabled=true` 时注册本地 TXT、Markdown 和 DOCX 生产解析器 Bean，纯本地内存处理，无外部调用，构造参数复用 `KnowledgeRagProperties.maxSourceSize`（绑定 `APP_FILE_MAX_SIZE`）。
  - `DocMindParserConfiguration` 仅在 `app.knowledge.rag.enabled=true` 且 `app.knowledge.rag.docmind.enabled=true` 时注册 PDF 云端解析器与客户端 Bean；未显式启用时绝不向外上传文件或产生云端计费。

## Token 预算切分规则与边界（`TokenBudgetKnowledgeChunker`）

生产切分策略实现为 `TokenBudgetKnowledgeChunker`，位于 `infrastructure.knowledge.indexing.chunk`，支持 `FLAT` 与 `PARENT_CHILD` 两种模式。由 `KnowledgeChunkerConfiguration` 在 `app.knowledge.rag.enabled=true` 时注册为 Spring Bean。

### 内核与 Token 预算

- **共用内核**：FLAT 与 PARENT_CHILD 两种模式共用底层的原文区间切分内核（`TokenRangeSplitter` 与 `SourceMappedText`），不依赖源文件格式。切分策略实例在构造时不绑定具体运行参数，而是在执行 `chunk(document, options)` 时消费传入的 `ChunkingOptions`。
- **本地 CL100K_BASE 计数**：Token 预算通过直接声明的 `com.knuddels:jtokkit:1.1.0`（`JTokkitTokenBudget`）基于 `CL100K_BASE` 编码进行本地精确计算（`countTokensOrdinary` 与 `encodeOrdinary`），不调用任何网络或模型服务端接口；文本中的特殊 Token 字面量（如 `<|endoftext|>`）按普通文本字面处理；不声称与任意模型提供商的服务端计数严格一致。
- **工作窗口与 BPE 适配**：切分前瞻窗口保守限定为预算的 16 倍 UTF-16 长度（下限 64），避免重复全篇编码；前缀截断后重新由 tokenizer 校验，若截断影响 BPE 编码则逐步收缩完整码点，确保最终片段 Token 数严格不超过预算。

### 局部 UTF-16 坐标与运行范围隔离

- **逻辑运行范围（Logical Run）**：文档输入块按结构与边界划分为隔离的连续范围：
  - 遇到显式硬边界（`boundaryBefore = true`）时强制隔离；
  - 遇到 `headingPath` 路径变化时强制隔离；
  - 遇到 `CODE` 或 `TABLE` 结构块时强制独立成段隔离。
- **短块合并**：同一逻辑运行范围内、相同 headingPath 的普通文本块使用固定 `\n\n`（`keepSeparator = false` 时使用两个空格 `"  "`）连接合并；跨运行范围不合并。
- **位置映射与全文偏移推导**：
  - 块间插入的合并分隔符由于在原文中不存在，跨块或包含合成分隔符的切片不伪造全文偏移，`SourceLocation` 的 `startOffset` 与 `endOffset` 保守置为 `null`；
  - 仅当切片完全来自单个原样等长的输入块时，按局部 UTF-16 相对坐标精确推导全文子偏移；若完整覆盖输入块，则直接沿用原来源范围；
  - 页码仅在整块覆盖或单页来源时可靠（`pageStart == pageEnd` 或完整覆盖）；覆盖部分多页块时页码保守置为 `null`；
  - 标题路径的 `null`（如纯文本段落）与空列表 `[]`（如无标题 Markdown 块）保持语义区别，不混同。

### 换行与分隔符语义（`keepSeparator`）

- `keepSeparator = true` 时原样保留换行符。
- `keepSeparator = false` 时，仅对普通文本的 CR（`\r`）与 LF（`\n`）一对一等长替换为空格（`' '`）；结构块（`CODE` 与 `TABLE`）始终保留原始换行排版。
- 无论开关状态，切分过程均**不删除标点符号、不执行 trim**。

### 候选边界判定与回退层级

在 Token 预算上限（`cap`）约束内，切分器优先寻找语义断句点；为避免过碎切分，若候选断点距离片段起点不足 `minChunkSizeChars`（短块软阈值）则继续向后寻找：

1. **段落边界**：连续双换行 `\n\n`（结构块不套用）；
2. **句子边界**：强句末标点（`。？！?!` 以及避开数字小数点和域名/缩写的 `.`）；
3. **次级边界**：单个换行 `\n` 或 `punctuationMarks` 中包含的其他标点；
4. **空白边界**：任意空白字符；
5. **预算安全截断**：若无上述合适边界，直接截断至 Token 预算允许的前缀（`cap`），并确保末尾不越过 BPE 编码预算。

### Unicode、短尾保护与超限拒绝

- **Unicode 保护**：切分基于 UTF-16 局部坐标，不保证 Unicode 字素簇完整（如复杂多码点修饰 emoji），但**绝不拆分代理对（surrogate pair）**；输入包含不完整或孤立的高低代理项时，在进入切分前抛出异常明确拒绝。
- **短尾不丢**：切分绝不丢弃尾部非空短文本；纯空白窗口可能不产生切片，但所有非空内容必须被切片完全覆盖。若 Token 预算过小无法容纳单个完整 Unicode 字符，明确抛出异常。
- **总切片数保护**：单篇文档生成的片段总数（包含 CONTEXT 与 RETRIEVAL 所有角色）若超出 `maxNumChunks`，明确抛出异常终止构建。

### 两种模式与父子关系

- **FLAT 模式**：直接按 `chunkSize` 切分，产出的所有 `ChunkGroupDraft` 中 `context = null`，只包含一个或多个 `RETRIEVAL` 子块；支持 `chunkOverlap` 重叠目标尺寸。
- **PARENT_CHILD 模式**：
  - 父块切分：按 `parentChunkSize` 将运行范围切分为互不重叠的父块（`overlap = 0`）；
  - 子块切分：在各父块范围内按 `chunkSize` 切分子检索块，支持 `chunkOverlap` 重叠目标尺寸；
  - **正推进重叠**：`chunkOverlap` 目标满足 `0 <= chunkOverlap < chunkSize`；重叠采用有界候选二分搜索确定重叠起点，每个候选通过实际 Token 计数校验（不保证最大重叠，可能缩小或取消），且下一块必须向前推进新内容（`covered` 终点严格递增），防止循环停滞；在硬边界、运行范围边界或父块边界处重置重叠；
  - **单一子块优化**：若一个父块范围内仅产生一个检索子块且其范围与父块完全一致，则不额外保存一份相同父块正文（`context = null`，直接作为无父检索块）；
  - **存储与索引分工**：父块（`CONTEXT`）只持久化到 MySQL，不进入向量索引；只有检索子块（`RETRIEVAL`）被提交给向量索引进行批量向量化。

### CODE 与 TABLE 结构块降级

- 结构块单独隔离为一个运行范围，标记 `lineOnly = true`；
- 始终保留换行排版，不进行普通段落/句子断句；过长时仅按行（`\n`）或安全长度退化切分；
- 不具有结构化表格行/表头语义或代码语法 AST 解析能力；切分逻辑无需修改文档 AST 或数据库 schema。

## 字段、位置与时间

领域与应用校验 `heading_path` 有序项、token_count 非负、偏移成对且满足 `0 <= start_offset < end_offset`、页码成对且满足 `1 <= page_start <= page_end`。偏移相对于同一处理后全文的 UTF-16 半开区间，不是原文件字节或 PDF 坐标；无法可靠映射时为空，正文长度不用于限制全文偏移。父子片段各自保存正文和可选来源范围，不根据父范围伪造子位置。

标题 JSON 的 NULL 与空数组分别保留。ID 使用 32 位 UUID；知识库时间通过共享 Clock 取 UTC 微秒，版本时间列使用 `created_at`，其余知识表使用 `create_at`。字段与索引以 [生产 SQL](../../deploy/mysql/schema.sql) 和 [H2 测试 SQL](../../src/test/resources/schema.sql) 为准。

## 表结构、配置与验证入口

四表以 SQL 为准，初始化脚本手动执行，不自动升级旧表。缺少 `parent_chunk_id`、`chunk_role` 或其索引时，需要另行手动迁移；保留旧片段 ID 与序号，并将旧平铺数据归为无父 RETRIEVAL。

普通测试真实调用本地 TXT/MD/DOCX 解析、PDFBox 容器校验与 Token 切分，DocMind 网关使用 Mock，构建集成使用 H2 和文件/云端/向量替身。它们不代表真实 MySQL、OpenAI、Qdrant 或云端 DocMind 验收。

| 入口 | 关注内容 |
| --- | --- |
| [基础服务](../../src/main/java/com/item/aisummary/application/knowledge/service/) 与 [构建编排](../../src/main/java/com/item/aisummary/application/knowledge/indexing/) | 内部调用、策略合同、组保存及版本发布 |
| [生产解析器](../../src/main/java/com/item/aisummary/infrastructure/knowledge/indexing/parse/) 与 [解析测试](../../src/test/java/com/item/aisummary/infrastructure/knowledge/indexing/parse/) | 格式边界、位置映射与预算防御 |
| [生产切分器](../../src/main/java/com/item/aisummary/infrastructure/knowledge/indexing/chunk/) 与 [切分测试](../../src/test/java/com/item/aisummary/infrastructure/knowledge/indexing/chunk/) | Token 预算、重叠、父子分组及本地构建集成 |
| [RAG 条件配置](../../src/main/java/com/item/aisummary/infrastructure/knowledge/indexing/config/) 与 [向量适配](../../src/main/java/com/item/aisummary/infrastructure/knowledge/indexing/vector/) | 开关、依赖检查与外部派生数据边界 |
| [知识库应用测试](../../src/test/java/com/item/aisummary/application/knowledge/) 与 [持久化测试](../../src/test/java/com/item/aisummary/infrastructure/knowledge/persistence/) | 生命周期、关系校验、事务回滚与字段往返 |

测试命令见 [项目 README](../../README.md#验证)。云端解析需另行显式验收；普通测试不上传文件或执行付费调用。
