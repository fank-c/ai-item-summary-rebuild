# 文档导航

启动与测试命令见根目录 [README](../README.md)，代理工作规则见 [AGENTS.md](../AGENTS.md)。本目录保留模块说明和按需查阅的参考资料。

| 文档 | 用途 |
| --- | --- |
| [工程结构](./reference/architecture.md) | 四层职责、包结构、跨模块调用与公共能力边界 |
| [用户模块](./modules/user.md) | 密码更新意图、逻辑删除与用户引用 |
| [文件模块](./modules/file.md) | 两次读流、文件归属、存储操作与资源关闭 |
| [知识库模块](./modules/knowledge.md) | 内部服务、解析与切分合同、父子片段、向量写入与版本发布 |
| [HTTP API](./reference/api.md) | 健康检查、用户与文件接口的参数、响应和错误约定 |
| [配置说明](./reference/configuration.md) | 配置加载、运行变量与条件装配 |

配置模板见 [`.env.example`](../.env.example)，生产表结构见 [schema.sql](../deploy/mysql/schema.sql)，自动测试表结构见 [测试 schema](../src/test/resources/schema.sql)。

## 维护边界

- 按任务查阅相关文档，无需每次通读或同步整个文档区。
- 公开 HTTP 合同集中维护在 `reference/api.md`；仅在路径、方法、参数、响应或错误合同变化时更新相关内容。
- 模块与架构说明只在职责、关键业务规则或设计边界变化时更新，不逐项追踪类清单或测试用例增减。
- 配置默认值以 `application.yml`、配置类和 `.env.example` 为准；数据库字段与索引以 SQL 为准，不另建重复的操作指南、测试说明或字段表。
- 新增文档须有明确用途；实际执行与测试结果在当次交付中说明，不保存任务日志、审查报告或历史测试报告。
- 移动或删除文档时修正相关链接。临时方案放在 `.work/plans/`，完成后删除。
