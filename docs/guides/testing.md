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
| 文件流程与规则 | 实际大小、SHA-256、流关闭、归属状态、Key 字节预算 | `FileServiceTest`、`FileRulesTest`、`FileHttpTest` |
| 文件 HTTP 边界 | 身份、Key、数据库单边失败、真实容器 multipart 上限 | `FileAuthenticationHttpTest`、`FileKeyValidationHttpTest`、`FileFailureHttpTest`、`FileMultipartHttpTest` |
| 存储与装配 | SDK 元数据、GET 签名、OSS 资源关闭、稳定异常、条件配置 | `StorageAdapterTest`、`FileConfigurationTest` |
| 公共错误 | 不支持的 Content-Type 返回 415、响应字段、协议头和请求标识 | `GlobalExceptionHandlerHttpTest` |
| 知识库领域与持久化 | 位置规则、四表 MyBatis Mapper/H2、联合唯一约束、标题 JSON、可空字段与 UTC 微秒 | `KnowledgeRulesTest`、`KnowledgePersistenceTest` |
| 知识库内部用例 | 多库同名、改名、文档关联与文件归属、版本递增及并发唯一冲突 | `KnowledgeServicesTest` |
| 知识片段事务 | 追加、排序、实际计数、非 building 拒绝、半批失败和计数更新失败回滚、并发追加 | `KnowledgeChunkServiceTest` |
| RAG 源读取与策略合同 | 原字节大小/SHA-256、流资源归属、parser/chunker 路由及数据合同 | `KnowledgeSourceReaderTest`、`KnowledgeProcessingContractsTest`、`KnowledgeProcessingRouterTest` |
| RAG 编排与生命周期 | 替身策略、组式保存、全部角色上限、精确向量批次、仅 RETRIEVAL 向量化、发布/失败/清理和事务行为 | `KnowledgeIndexBuildServiceTest`、`KnowledgeChunkGroupsTest`、`KnowledgeBuildLifecycleServiceIntegrationTest` |
| 向量适配与配置 | VectorStore 替身上的 UUID/metadata/过滤/安全异常；默认关闭、缺策略或依赖时拒绝启动 | `SpringAiKnowledgeVectorIndexTest`、`KnowledgeRagConfigurationTest`、`KnowledgeRagPropertiesTest` |

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

只检查知识库四表和内部服务时：

```bash
mvn -Dtest=KnowledgeRulesTest,KnowledgePersistenceTest,KnowledgeServicesTest,KnowledgeChunkServiceTest test
```

检查固定构建编排、组式保存与发布时：

```bash
mvn -Dtest=KnowledgeSourceReaderTest,KnowledgeProcessingContractsTest,KnowledgeProcessingRouterTest,SpringAiKnowledgeVectorIndexTest,KnowledgeChunkGroupsTest,KnowledgeBuildLifecycleServiceIntegrationTest,KnowledgeIndexBuildServiceTest,KnowledgeRagConfigurationTest,KnowledgeRagPropertiesTest test
```

构建编排的 parser/chunker 和 VectorStore 均使用替身；不测试生产 Reader/Splitter，也不进行真实解析、切分、OpenAI 或 Qdrant 调用。发布 SQL 失败使用 H2 临时 CHECK 约束注入，不修改生产规则。

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

当前没有生产 parser/chunker，也没有可执行的真实 RAG 端到端验收流程。不要按变量配置后声称 txt/md 入库可用；RAG 显式开启会因为策略缺失而拒绝启动。新增真实解析/切分策略与外部验收需作为后续独立实现和验证任务。当前可验证范围仅为固定编排、策略替身、H2 生命周期/组保存和 VectorStore 替身合同。

RAG 真实验收至少待补：生产 parser 的格式/UTF-8/结构与来源位置验证、生产 FLAT 及可选 PARENT_CHILD chunker 的边界与质量验证、真实 MySQL、OpenAI Embedding 和 Qdrant 协议/元数据/失败清理验收，以及活动版本检索过滤（检索本身目前不存在）。在这些实现和后端准备前，不配置个人凭据、不创建验收资源，也不把 H2 或 Mock 结果描述为真实服务验收。

## 验证结论的边界

| 已执行的验证 | 能证明什么 | 仍需另行验证 |
| --- | --- | --- |
| 普通 H2 测试 | 测试环境中的行为与 Mapper SQL 合同 | MySQL 方言及真实连接 |
| SDK Mock、内存存储与离线签名 | 调用参数、资源释放、异常转换和签名生成 | 真实 Bucket 的权限、上传下载删除 |
| 策略替身、H2 与 VectorStore 替身 | 固定编排、分组保存、生命周期和适配器输入合同 | 真实解析/切分、OpenAI/Qdrant 协议、权限与外部一致性 |
| `GET /api/health` | HTTP 服务启动并可达 | 数据库、模型及对象存储可用性 |
| `mvn -DskipTests clean verify` | 编译和打包 | 测试行为是否通过 |
| `mvn -Pmysql-it verify` 成功 | 配置的专用 MySQL 用户生命周期 | 文件存储、模型及其他外部能力 |

报告验证结果时，以当次实际命令与输出为证据，并说明未执行的检查。不能把编译成功描述为行为测试通过，也不能将 H2 或 Mock 的结果作为真实外部服务的验收。
