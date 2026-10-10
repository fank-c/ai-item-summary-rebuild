# HTTP API

本文档区描述当前生产源码中已实现的 HTTP 接口：健康检查、用户资料 CRUD（创建、查询、修改、删除）和核心文件接口。知识库仅提供内部 Java 服务；对话、摘要生成和 RAG 尚未提供 HTTP 接口。

返回 [文档导航](../../README.md)。首次调用先按 [运行指南](../../guides/getting-started.md) 启动应用，再阅读 [公共约定与错误响应](./common.md)。

## 接口总览

| 方法 | 路径 | 成功状态 | 响应 | 详细说明 |
| --- | --- | --- | --- | --- |
| GET | `/api/health` | `200` | 状态对象 | [健康检查](./health.md) |
| POST | `/api/users` | `201` | 用户对象 | [创建用户](./users.md#创建用户) |
| GET | `/api/users` | `200` | 用户数组 | [查询用户列表](./users.md#查询用户列表) |
| GET | `/api/users/{id}` | `200` | 用户对象 | [查询用户详情](./users.md#查询用户详情) |
| PUT | `/api/users/{id}` | `200` | 用户对象 | [修改用户](./users.md#修改用户) |
| DELETE | `/api/users/{id}` | `204` | 无响应体 | [删除用户](./users.md#删除用户) |
| POST | `/api/files?storageType=minio` | `201` | 文件对象 | [服务端上传](./files.md#服务端上传) |
| GET | `/api/files/{fileId}/download-url` | `200` | 临时下载地址 | [获取下载地址](./files.md#获取下载地址) |
| DELETE | `/api/files/{fileId}` | `204` | 无响应体 | [删除文件](./files.md#删除文件) |

文件上传的 `storageType` 支持 `minio` 或 `aliyun`，对应适配器必须已启用。当前没有文件列表、详情 GET、直传或知识库 HTTP 接口。

## 文档分工

- [公共约定](./common.md)：服务地址、媒体类型、模拟身份、请求标识、ProblemDetail 和错误码。
- [健康检查](./health.md)：请求、响应与检查边界。
- [用户接口](./users.md)：用户响应模型、CRUD 参数、密码规则、并发边界和 Bash / PowerShell 示例。
- [文件接口](./files.md)：上传、下载地址、删除、公开字段与访问限制。

内部 Java 合同见 [用户模块](../../modules/user.md)、[文件模块](../../modules/file.md)和[知识库模块](../../modules/knowledge.md)。测试专用路由不属于公开 API。

## 维护约定

新增或修改公开接口时，同步对应业务页面、公共约定和本页总览，并检查参数、状态码、响应结构与调用示例。源码及测试入口列在对应页面；验证方法见 [测试说明](../../guides/testing.md)，开发规则见 [AGENTS.md](../../../AGENTS.md)。
