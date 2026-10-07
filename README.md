# AI Item Summary

基于 Spring Boot 和 Spring AI 的学习工程。当前提供可启动的基础工程和健康检查，对话、RAG 等功能按后续学习需求逐步加入。

开发前阅读 [项目规则](./AGENTS.md)。当前公开 HTTP 接口见 [API 文档](./docs/api.md)。

## 模块结构

项目采用单个 Maven 工程，通过 Java 包划分四层职责，并在层内按业务分包：

| 包 | 职责 |
| --- | --- |
| `web` | HTTP 请求校验、协议转换、响应与异常映射 |
| `application` | 用例编排，以及外部能力接口定义 |
| `domain` | 各功能的领域概念与业务规则 |
| `infrastructure` | AI 模型、Prompt 和其他按需引入的外部依赖适配 |

依赖方向为 `web → application → domain`；`infrastructure` 实现应用层接口，由 Spring 装配。用例通过应用层定义的接口使用 AI 能力，Spring AI 调用封装在基础设施层。

当前只有启动类和健康检查。功能包和类按需求逐步创建，不提前创建空目录。Prompt 模板统一放在 `src/main/resources/prompts/`，测试包与生产代码的包结构对应。

完整的职责边界与扩展示例见 [项目规则中的目录与职责](./AGENTS.md#目录与职责)。

## 技术基线

- Java 21
- Spring Boot 4.1.1
- Spring AI 2.0.1
- Maven 3.9+

## 启动项目

模型默认关闭，无需数据库或 API Key 即可启动：

```bash
mvn spring-boot:run
```

检查服务状态：

```bash
curl -i http://localhost:8080/api/health
```

PowerShell：

```powershell
Invoke-RestMethod -Method Get -Uri 'http://localhost:8080/api/health'
```

预期 HTTP 状态为 `200`，响应为：

```json
{"status":"UP"}
```

健康检查只验证 HTTP 服务是否可达，不探测数据库或模型服务。

## 环境变量

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP 端口 |
| `SPRING_AI_MODEL_CHAT` | `none` | 设为 `openai` 启用模型 |
| `OPENAI_API_KEY` | 空 | 模型凭据，通过本地环境提供 |
| `OPENAI_MODEL` | `gpt-5-mini` | 模型名称 |

## 启用 OpenAI

后续学习模型调用时，通过本地环境提供 `OPENAI_API_KEY`，并启用模型：

PowerShell：

```powershell
# 先在本地环境中提供 OPENAI_API_KEY，不把真实凭据写入仓库。
$env:SPRING_AI_MODEL_CHAT = 'openai'
mvn spring-boot:run
```

Bash：

```bash
# 先在本地环境中提供 OPENAI_API_KEY。
export SPRING_AI_MODEL_CHAT=openai
mvn spring-boot:run
```

默认模型为 `gpt-5-mini`，可通过 `OPENAI_MODEL` 覆盖。启用模型只配置模型组件，当前尚未提供模型调用接口。

## 构建与测试

```bash
mvn clean verify
```

当前包含基础 Spring Boot 上下文测试和健康检查方法测试。独立测试配置强制关闭模型并清空 API Key，不依赖数据库、模型服务或个人凭据。

不提交 `.env`、本地 IDE 配置或 `target/` 生成物。
