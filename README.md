# AI Item Summary

基于 Java 21、Spring Boot 4.1.1 和 Spring AI 2.0.1 的学习工程，逐步复现 AI 应用的常见功能。当前已实现用户、文件、知识库基础服务和 txt/md RAG 同步入库；检索与问答按后续学习需求加入。

## 当前能力

| 模块 | 已实现内容 | 实现说明 |
| --- | --- | --- |
| 用户 | 资料 CRUD、BCrypt 密码摘要、逻辑删除、跨模块用户引用 | [用户模块](./docs/modules/user.md) |
| 文件 | 服务端上传、临时下载地址、删除、带归属校验的内部内容读取 | [文件模块](./docs/modules/file.md) |
| 知识库 | 四表持久化、基础内部服务、两层上下文/检索片段存储、txt/md 读取切分、OpenAI Embedding、Qdrant 入库与版本发布 | [知识库模块](./docs/modules/knowledge.md) |
| 公共能力 | 模拟请求身份、ProblemDetail 错误响应、请求标识、健康检查 | [工程结构与边界](./docs/architecture.md) |

用户、文件及知识库元数据使用 MySQL，文件对象按配置存入 MinIO 或阿里云 OSS。H2 仅用于自动测试。聊天模型、Embedding、Qdrant、RAG 构建和对象存储默认关闭。基础元数据服务不调用模型；启用 txt/md 入库后才调用 Embedding 与向量存储。

知识库能力通过内部 Java 服务提供。txt/md 入库入口为 `KnowledgeIndexBuildService.build(actorUserId,knowledgeDocumentId)`，启用步骤见 [运行指南](./docs/guides/getting-started.md#6-启用-txtmd-入库)。当前没有知识库 HTTP、访问权限、检索或问答；文件归属检查不等于知识库授权。片段存储支持 CONTEXT/RETRIEVAL 角色和同版本父引用，尚未实现父子切分或父块检索。字段与约束见 [知识库表结构](./docs/reference/database/knowledge.md#knowledge_chunk)，旧表需要另行手动升级，初始化脚本不会自动添加新列。

## 快速开始

需要 Java 21、Maven 3.9+ 和可用的 MySQL。工作目录设为项目根目录。

1. 创建并选中数据库，在 MySQL 客户端执行 `SOURCE deploy/mysql/schema.sql;`。应用不自动建表，脚本不会升级已有表。
2. 从 [`.env.example`](./.env.example) 创建 `.env`，填写 `DB_URL`、`DB_USERNAME` 和 `DB_PASSWORD`。已有 `.env` 时保留本地值。
3. 运行 `mvn spring-boot:run`。应用自动读取工作目录中的 `.env`。
4. 请求 `GET http://localhost:8080/api/health`，预期返回 `200` 和 `{"status":"UP"}`。

完整建表、PowerShell / Bash 命令和文件存储准备步骤见 [运行指南](./docs/guides/getting-started.md)。健康检查只证明 HTTP 服务可达，不探测数据库、模型或对象存储。

## 文档入口

[文档导航](./docs/README.md) 按阅读目的组织内容：

| 目的 | 文档 |
| --- | --- |
| 启动应用 | [运行指南](./docs/guides/getting-started.md) |
| 填写本地配置、查默认值 | [配置说明](./docs/reference/configuration.md) |
| 理解层、业务、职责三级分包和公共能力 | [工程结构](./docs/architecture.md) |
| 学习模块实现与调用链路 | [用户模块](./docs/modules/user.md)、[文件模块](./docs/modules/file.md)、[知识库模块](./docs/modules/knowledge.md) |
| 调用 HTTP 接口、查询错误码 | [接口总览](./docs/reference/api/README.md)、[公共约定与错误响应](./docs/reference/api/common.md) |
| 查询知识库字段和数据约束 | [知识库表结构](./docs/reference/database/knowledge.md) |
| 运行测试、确认验证边界 | [测试说明](./docs/guides/testing.md) |

开发代理应先阅读 [AGENTS.md](./AGENTS.md)。工程采用单 Maven、四层结构，层内按业务和职责分包；请求、用例、领域模型和持久化文件的具体位置见 [包结构与命名](./docs/architecture.md#包结构与命名)。文档区只维护当前有效说明；临时方案在本地 `.work/plans/` 中维护，不纳入提交，执行与验证完成并同步现行文档后删除。

## 构建与测试

```bash
mvn clean verify
```

普通测试使用独立 H2、真实本地 Reader/Splitter 和存储替身，不导入本地 `.env`，也不访问真实模型、Qdrant 或对象存储。真实 MySQL 用户验收通过 `mvn -Pmysql-it verify` 单独开启，需先设置专用测试库的进程环境变量，详见 [测试说明](./docs/guides/testing.md)。
