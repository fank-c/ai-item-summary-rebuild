# 测试与验证

普通测试使用 H2 和对象存储替身，模型始终关闭，不读取本地 `.env` 或个人凭据。测试入口位于 `src/test/java/com/item/aisummary/`，包结构与生产代码对应。

返回 [文档导航](../README.md)。本文维护测试方式和验证边界，不保存任务执行记录或历史测试结果。

## 完整构建

在项目根目录执行：

```bash
mvn clean verify
```

该命令清理旧产物、编译、执行普通测试并打包。依赖或配置变更还需确认解析结果和无模型凭据时的上下文启动；启动行为变更需实际启动并请求健康接口。

## 测试分布

| 范围 | 覆盖内容 | 代表测试 |
| --- | --- | --- |
| 基础与配置 | Spring 上下文、健康接口、`.env` 加载与优先级、普通测试配置隔离 | `AiItemSummaryApplicationTests`、`HealthControllerTest`、`AiItemSummaryEnvironmentTest` |
| 用户 HTTP 与应用 | CRUD、密码边界、六字段响应、错误信息隐藏、写入行数 | `UserHttpTest`、`UserFailureHttpTest`、`UserServiceTest` |
| 用户持久化与摘要 | H2 真 Mapper 更新列、旧摘要保护、UTC 微秒、BCrypt | `UserRepositoryTest`、`BCryptPasswordHasherTest` |
| 身份与引用 | 模拟注入清理、关闭后的行为、Unicode 码点限制、引用实现切换 | `MockAuthenticationWebTest`、`MockAuthenticationDisabledWebTest`、`ActorUserIdRulesTest`、`UserReferenceConfigurationTest` |
| 文件流程与规则 | 实际大小、SHA-256、流关闭、归属状态、长原名与 Key 解耦、总字节与单段预算 | `FileServiceTest`、`FileRulesTest`、`FileHttpTest` |
| 文件 HTTP 边界 | 身份、Key、历史 Key 透传、数据库单边失败、真实容器单文件/请求/part 头部上限与长 UTF-8 名称 | `FileAuthenticationHttpTest`、`FileKeyValidationHttpTest`、`FileFailureHttpTest`、`FileMultipartHttpTest`、`FileLongFilenameMultipartHttpTest` |
| 存储与装配 | SDK 元数据、GET 签名、OSS 资源关闭、稳定异常、条件配置 | `StorageAdapterTest`、`FileConfigurationTest` |
| 公共错误 | 不支持的 Content-Type 返回 415、响应字段、协议头和请求标识 | `GlobalExceptionHandlerHttpTest` |
| 知识库领域与持久化 | SourceLocation 位置规则、四表 MyBatis Mapper/H2、联合唯一约束、标题 JSON、可空字段与 UTC 微秒 | `KnowledgeRulesTest`、`SourceLocationTest`、`KnowledgePersistenceTest` |
| 知识库内部用例 | 多库同名、改名、文档关联与文件归属、版本递增及并发唯一冲突 | `KnowledgeServicesTest` |
| 知识片段事务 | 追加、排序、实际计数、非 building 拒绝、半批失败和计数更新失败回滚、并发追加 | `KnowledgeChunkServiceTest` |
| 生产文档解析器与解码 | 真实调用 TXT/MD 解析行为（无网络请求）、严格 UTF-8/BOM/LF 规整、顶层块提取、标题路径与硬边界、安全预算及装配 | `TextKnowledgeDocumentParserTest`、`MarkdownKnowledgeDocumentParserTest`、`Utf8TextDecoderTest`、`KnowledgeParserConfigurationTest` |
| 生产切分器与本地预算 | 真实调用 TokenBudget 切分行为（CL100K_BASE 预算、段落/句子/标点/空白回退、代理对保护、短尾保留、重叠正进度、父子无重复、keepSeparator 语义与超限失败） | `TokenBudgetKnowledgeChunkerTest` |
| 分块构建集成 | 真实本地 parser/chunker、H2 Mapper/发布，配合文件/向量替身验证完整构建链路（清楚非外部端到端） | `KnowledgeChunkingBuildIntegrationTest` |
| 文本解析构建集成 | 真实 TXT/MD 解析产出正文、标题路径、偏移的入库与发布流转（隔离 H2 + 分块/向量替身，非外部端到端） | `KnowledgeTextParsingBuildIntegrationTest` |
| RAG 源与解析合同 | 原字节大小/SHA-256、流资源归属、格式声明及解析数据合同 | `KnowledgeSourceReaderTest`、`FileFormatTest`、`KnowledgeParseContractsTest` |
| RAG 路由与构建生命周期 | 策略路由、分块替身策略、全部角色上限、精确向量批次、仅 RETRIEVAL 向量化、发布/失败/清理和事务行为 | `KnowledgeProcessingRouterTest`、`KnowledgeIndexBuildServiceTest`、`KnowledgeBuildLifecycleServiceIntegrationTest` |
| RAG 分块与组写入 | 分块合同、ChunkingOptions 参数约束（parentChunkSize、chunkOverlap）、整组序号/角色/父序号映射与基础保存服务代理调用 | `KnowledgeChunkerContractsTest`、`ChunkingOptionsTest`、`KnowledgeChunkGroupWriterTest`、`KnowledgeChunkServiceTest` |
| 向量适配与配置 | VectorStore 替身上的 UUID/metadata/过滤/安全异常；RAG 开启时两模式切分器装配、属性绑定、默认关闭与缺外部依赖时拒绝启动 | `SpringAiKnowledgeVectorIndexTest`、`KnowledgeChunkerConfigurationTest`、`KnowledgeRagConfigurationTest`、`KnowledgeRagPropertiesTest` |

[用户说明](../modules/user.md#测试与学习入口)、[文件说明](../modules/file.md#测试与学习入口) 和 [知识库说明](../modules/knowledge.md#表结构配置与验证入口) 提供测试源码链接及对应规则。

## 定向运行

只调试用户更新边界时：

```bash
mvn -Dtest=UserServiceTest,UserRepositoryTest,UserHttpTest test
```

只检查文件规则与上传服务时：

```bash
mvn -Dtest=FileRulesTest,FileServiceTest,FileKeyValidationHttpTest test
```

检查长文件名修复与 multipart 头部预算时：

```bash
mvn -Dtest=FileRulesTest,FileServiceTest,FileHttpTest,FileKeyValidationHttpTest,FileMultipartHttpTest,FileLongFilenameMultipartHttpTest,AiItemSummaryEnvironmentTest test
```

`FileLongFilenameMultipartHttpTest` 显式加载生产头部配置，再以测试 YAML 覆盖外部后端并禁用 `.env` 导入；真实容器配合 H2 与存储替身验证长名称保留和 400/413 边界。配置探针另外验证 4KB 默认值及本地文件、进程环境和命令行覆盖，不使用个人凭据。历史超长 Key 用替身验证不被新规则拒绝，不表示当前 MinIO 能存放这些历史对象。

只检查知识库领域、四表、基础服务和片段事务时：

```bash
mvn -Dtest=KnowledgeRulesTest,SourceLocationTest,KnowledgePersistenceTest,KnowledgeServicesTest,KnowledgeChunkServiceTest test
```

检查生产 TXT/MD 解析器、UTF-8 解码与解析器装配时：

```bash
mvn -Dtest=Utf8TextDecoderTest,TextKnowledgeDocumentParserTest,MarkdownKnowledgeDocumentParserTest,KnowledgeParserConfigurationTest test
```

检查生产切分策略（TokenBudgetKnowledgeChunker）与配置时：

```bash
mvn -Dtest=TokenBudgetKnowledgeChunkerTest,KnowledgeChunkerConfigurationTest,ChunkingOptionsTest,KnowledgeRagPropertiesTest test
```

检查分块构建集成（真实 parser/chunker + H2 Mapper/发布 + 文件/向量替身）时：

```bash
mvn -Dtest=KnowledgeChunkingBuildIntegrationTest test
```

检查文本解析构建集成（真实解析器 + H2 + 分块/向量替身，清楚非外部端到端）时：

```bash
mvn -Dtest=KnowledgeTextParsingBuildIntegrationTest test
```

检查 RAG 源/解析/分块合同、路由、组 writer、构建生命周期、向量适配与配置时：

```bash
mvn -Dtest=KnowledgeSourceReaderTest,FileFormatTest,KnowledgeParseContractsTest,KnowledgeProcessingRouterTest,KnowledgeChunkerContractsTest,KnowledgeChunkGroupWriterTest,KnowledgeIndexBuildServiceTest,KnowledgeBuildLifecycleServiceIntegrationTest,SpringAiKnowledgeVectorIndexTest,KnowledgeRagConfigurationTest,KnowledgeRagPropertiesTest test
```

生产解析器与切分器通过独立单元测试真实调用验证行为，不访问网络；`KnowledgeChunkingBuildIntegrationTest` 与 `KnowledgeTextParsingBuildIntegrationTest` 在隔离 H2 下配合替身验证真实 parser 与 chunker 产出入库与状态流转，清楚非外部端到端；构建编排的 VectorStore 使用替身，不声称真实模型完整入库，不访问外部 OpenAI 或 Qdrant 服务。发布 SQL 失败使用 H2 临时 CHECK 约束注入，不修改生产规则。

片段失败回滚测试直接调用 Spring 装配的服务，测试本身不包事务。唯一冲突和 SQL 失败后重新查询已提交数据，检查没有留下部分片段或错误计数；另外验证不兼容外层事务被拒绝、兼容外层事务共同回滚。测试用 CHECK 约束只存在于独立 H2 中，不写入正式 schema。

定向测试不能代替代码变更后的完整 `mvn clean verify`。注释或文档整理可复用现有行为测试，无需为注释新增测试。

## 真实 MySQL 用户验收

[UserMySqlIT](../../src/test/java/com/item/aisummary/web/user/controller/UserMySqlIT.java) 只在 `mysql-it` profile 中由 Failsafe 执行。它通过 `System.getenv()` 读取以下进程环境变量：

- `USER_TEST_DB_URL`：独立测试库，库名必须以 `_test` 结尾，URL 保留 UTC 参数。
- `USER_TEST_DB_USERNAME`：具备测试库建表与 CRUD 权限的账户。
- `USER_TEST_DB_PASSWORD`：本地密码，默认空。

`.env` 不修改进程环境，因此只写入 `.env` 不会配置这项验收。下例的 URL 和账户是虚构占位值，需替换成本地测试库；密码由本地安全方式设置。

Bash：

```bash
export USER_TEST_DB_URL='jdbc:mysql://localhost:3306/ai_item_summary_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
export USER_TEST_DB_USERNAME='test-account'
mvn -Pmysql-it verify
```

PowerShell：

```powershell
$env:USER_TEST_DB_URL = 'jdbc:mysql://localhost:3306/ai_item_summary_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:USER_TEST_DB_USERNAME = 'test-account'
mvn -Pmysql-it verify
```

测试执行正式 schema 和完整用户 HTTP CRUD，检查摘要保留、状态、删除和 UTC 微秒；只清理本次创建的记录，不清空全表。脚本不会升级已有旧表。

缺 URL、账户或库名不符合要求时会失败。该验收覆盖用户模块，不等于文件或知识库模块的真实 MySQL 验证，也不等于对象存储验收。

## 当前 RAG 验收边界

当前已实现 TXT 和 Markdown 两种生产文档解析器，以及生产切分策略 `TokenBudgetKnowledgeChunker`（支持 FLAT 与 PARENT_CHILD 模式），并通过独立单测验证真实行为（不访问网络）；构建集成测试（`KnowledgeChunkingBuildIntegrationTest`、`KnowledgeTextParsingBuildIntegrationTest`）在隔离 H2 下配合替身验证本地构建链路与状态流转，清楚非外部端到端。缺少 OpenAI Embedding 或 Qdrant 等外部后端依赖或配置时仍拒绝启动；解析与切分实现不代表真实外部服务已验收或生产端到端完成。当前可验证范围为解析器与切分器单测真实调用、本地构建集成测试、构建编排固定流程、H2 生命周期/组保存和 VectorStore 替身合同。

RAG 真实外部验收至少待补：真实 MySQL、OpenAI Embedding 和 Qdrant 协议/元数据/失败清理验收，以及活动版本检索过滤（检索本身目前不存在）。在这些后端准备和验收前，不配置个人凭据、不创建验收资源，也不把 H2 或替身结果描述为真实服务验收。

## 验证结论的边界

| 已执行的验证 | 能证明什么 | 仍需另行验证 |
| --- | --- | --- |
| 普通 H2 测试 | 测试环境中的行为与 Mapper SQL 合同 | MySQL 方言及真实连接 |
| SDK Mock、内存存储与离线签名 | 调用参数、资源释放、异常转换和签名生成 | 真实 Bucket 的权限、上传下载删除 |
| 解析器与切分器单测真实调用、构建集成测试、H2 与 VectorStore 替身 | TXT/MD 解析与切分行为、本地构建入库流转、固定编排、分组保存、生命周期和适配器输入合同 | OpenAI/Qdrant 外部协议、权限与外部一致性 |
| `GET /api/health` | HTTP 服务启动并可达 | 数据库、模型及对象存储可用性 |
| `mvn -DskipTests clean verify` | 编译和打包 | 测试行为是否通过 |
| `mvn -Pmysql-it verify` 成功 | 配置的专用 MySQL 用户生命周期 | 文件存储、模型及其他外部能力 |

报告验证结果时，以当次实际命令与输出为证据，并说明未执行的检查。不能把编译成功描述为行为测试通过，也不能将 H2 或 Mock 的结果作为真实外部服务的验收。
