# 文档导航

本目录维护当前项目的运行方式、实现说明和参考资料。知识库目前通过内部 Java 服务调用，没有公开 HTTP 接口。

## 按阅读目的进入

| 目的 | 推荐阅读顺序 |
| --- | --- |
| 启动项目 | [运行指南](./guides/getting-started.md) → [配置说明](./reference/configuration.md) → [健康检查](./reference/api/health.md) |
| 调用接口 | [接口总览](./reference/api/README.md) → [公共约定](./reference/api/common.md) → [用户接口](./reference/api/users.md)或[文件接口](./reference/api/files.md) |
| 学习实现 | [工程结构](./architecture.md) → 对应模块说明 → 模块列出的源码与测试 |
| 验证改动 | [测试说明](./guides/testing.md) → 对应模块的测试入口 |

## 操作指南

| 文档 | 内容 |
| --- | --- |
| [运行指南](./guides/getting-started.md) | 准备 MySQL、建表、创建 `.env`、启动应用、启用文件存储与 txt/md 入库 |
| [测试说明](./guides/testing.md) | 普通测试、定向测试、真实 MySQL 用户验收及验证边界 |

## 架构与模块

| 文档 | 内容 |
| --- | --- |
| [工程结构](./architecture.md) | 四层职责、包结构、依赖方向、请求身份、公共错误与 UTC 时间 |
| [用户模块](./modules/user.md) | CRUD、密码处理、按意图更新字段、逻辑删除和用户引用 |
| [文件模块](./modules/file.md) | 两次读流、元数据与对象存储、归属校验、签名和资源管理 |
| [知识库模块](./modules/knowledge.md) | 内部服务、Reader/Splitter、向量索引、版本发布与失败清理 |

## 参考资料

| 文档 | 内容 |
| --- | --- |
| [配置说明](./reference/configuration.md) | 配置加载方式、运行变量、默认值、存储与 RAG 开关及专用测试变量 |
| [HTTP API](./reference/api/README.md) | 已实现接口的参数、响应、状态码、错误码与调用示例 |
| [知识库表结构](./reference/database/knowledge.md) | 当前四表的字段、可空性、索引、数据关系和设计限制 |

## 维护约定

- 根目录 [README](../README.md) 保留项目定位、最短启动步骤和文档入口；[AGENTS.md](../AGENTS.md) 维护代理开发规则。
- `guides/` 维护操作步骤；`reference/configuration.md` 集中维护配置语义、变量和默认值。
- `modules/` 解释内部调用、核心流程、业务规则和实现原因，保留关键源码及测试链接；HTTP 详细合同统一放在 `reference/api/`。
- `reference/database/` 描述现行表结构，保留影响开发的设计理由；不记录旧项目迁移过程。
- 临时方案放在项目根目录的 `.work/plans/`，由 `.gitignore` 排除，不纳入提交。执行完成、所需功能检查通过且现行文档同步后删除；未完成时暂留本地。
- 不在文档区保存任务执行记录、审查报告或历史测试结果。测试说明维护验证方法与边界，实际执行结果在当次交付中说明。

新增或移动文档时同步本导航，并检查相对链接和页内锚点。模块文档按“学习目标与范围 → 调用方式 → 核心流程与规则 → 失败边界 → 源码与测试入口”组织，根据实际内容设置章节。
