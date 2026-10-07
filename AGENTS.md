# AGENTS.md

## 适用范围

本文件适用于整个项目目录。编码代理在读取和修改项目文件前，必须先遵守本文件；用户当前指令优先于本文件。

## 项目定位

本项目主要用于学习和复现 AI 应用中的常见功能，以 Spring Boot + Spring AI 为基础，逐步形成可运行、可测试、可说明的示例。

后续功能会按独立的小主题逐步加入，例如：

- 对话：理解模型调用、消息上下文和基础会话流程。
- RAG（检索增强生成）：理解文档加载、切分、向量检索和生成回答的完整链路。
- 其他 AI 能力：根据实际学习目标逐步扩展，不预先引入无关组件。

每个功能都应说明学习目标、关键 Spring AI API、运行方式、输入输出和测试方式。优先保持实现简单、边界清晰，只有在功能确实需要时才引入数据库、向量数据库、鉴权或生产级治理能力。

当前阶段只维护可启动的基础工程和健康检查。新增业务模块、数据库、鉴权或其他基础设施前，必须有明确的用户需求。

## 技术基线

- Java：21。
- Spring Boot：4.1.1。
- Spring AI：2.0.1。
- 构建工具：Maven 3.9+。
- 包名根路径：`com.item.aisummary`。
- 依赖版本统一由 `pom.xml` 管理，Spring AI 依赖使用 `spring-ai-bom`。

除非用户明确要求，不要自行升级 Spring Boot、Spring AI 或 Java 版本。升级时必须检查兼容性，并同步更新 `pom.xml`、`README.md` 和本文件。

## 修改前检查

1. 先读取相关源码、配置、测试和 README，确认现有行为。
2. 使用项目已有结构和命名，不要为了小改动进行无关重构。
3. 修改依赖或配置前，确认对应版本的官方文档和 Maven 坐标。
4. 发现需求涉及多个独立模块时，先拆分范围，再逐步实现。

## 目录与职责

当前源码结构：

```text
src/main/java/com/item/aisummary/
├── AiItemSummaryApplication.java
└── web/
    └── HealthController.java

src/main/resources/
└── application.yml

src/test/java/com/item/aisummary/
├── AiItemSummaryApplicationTests.java
└── web/
    └── HealthControllerTest.java

src/test/resources/
└── application.yml

docs/
└── api.md
```

### 工程组织原则

- 当前采用单个 Maven 工程，通过 Java 包划分模块职责。
- 保留 `web / application / domain / infrastructure` 四层结构，层内按业务分包，例如 `web.summary`、`application.summary` 和 `domain.summary`；对话、RAG 等功能沿用相同方式。
- 按实际业务需求逐步创建包和类，不提前创建空目录，也不为了凑齐层次创建无职责的类。
- 当前不拆分多个 Maven 模块。后续确有独立复用、发布或依赖隔离需求时，再评估拆分。

### 四层职责

| 层 | 职责 | 边界 |
| --- | --- | --- |
| `web` | Controller、请求参数校验、HTTP 协议转换、响应映射和统一异常映射 | 不承载业务规则，不直接调用模型、数据库客户端或第三方 HTTP SDK |
| `application` | 应用服务和用例编排，协调领域规则与外部能力 | 通过本层定义的接口调用外部能力，不依赖基础设施实现类 |
| `domain` | 各功能的领域概念、值对象与业务规则 | 不依赖 Spring Web、Spring AI、数据库客户端或模型供应商 SDK |
| `infrastructure` | AI 模型调用、Prompt 加载，以及按需增加的持久化或外部 HTTP 服务适配 | 实现应用层接口，封装外部协议、供应商细节和异常转换 |

领域层保持轻量。只有出现实际业务规则时，才引入领域服务、实体或其他抽象；有存储需求时，再定义相应的仓储接口。

### 依赖方向

```text
web ───────────────→ application ─────→ domain
                          ↑               ↑
                    infrastructure ───────┘
```

- `web` 调用 `application`，`application` 使用 `domain` 并定义所需的外部能力接口。
- `infrastructure` 实现应用层接口，可以使用领域类型；`domain` 不反向依赖其他层。
- Spring 启动配置将接口实现注入应用服务。具体适配实现及其装配配置放在基础设施边界内。

### 摘要用例的扩展示例

以下是后续实现摘要业务时的职责示例，不是当前已经存在的文件，也不要求一次性全部创建：

```text
src/main/java/com/item/aisummary/
├── AiItemSummaryApplication.java
├── web/
│   ├── HealthController.java
│   ├── summary/
│   │   ├── SummaryController.java
│   │   ├── CreateSummaryRequest.java
│   │   └── SummaryResponse.java
│   └── error/
│       └── GlobalExceptionHandler.java
├── application/
│   └── summary/
│       ├── GenerateSummaryService.java
│       ├── GenerateSummaryCommand.java
│       └── port/
│           └── SummaryGenerator.java
├── domain/
│   └── summary/
│       ├── Summary.java
│       └── SummaryOptions.java
└── infrastructure/
    └── ai/
        ├── SpringAiSummaryGenerator.java
        └── AiConfiguration.java

src/main/resources/
├── application.yml
└── prompts/
    └── summary.st
```

`SummaryGenerator` 表达应用所需的摘要生成能力；`SpringAiSummaryGenerator` 通过 Spring AI 实现该接口。应用服务负责组织“接收输入 → 协调领域规则 → 调用生成接口 → 返回结果”的流程，Controller 只负责请求与响应转换。

可复用或需要版本管理的 Prompt 放在 `src/main/resources/prompts/`，由基础设施层加载。模型名称与调用参数通过配置管理，应用层只传递业务所需的摘要选项。

测试包与对应生产代码的包结构保持一致。应用服务测试使用假的生成器或 Mock，不依赖真实模型。

## 编码约定

- 使用构造器注入，不使用字段注入。
- 类、方法和变量使用清晰的英文名称。
- 简单且不可变的请求、响应对象优先使用 `record`。
- API 路径使用 `/api` 前缀；新增业务接口优先使用 `/api/v1`。
- 配置项放在 `application.yml` 或配置类中，不把可变配置硬编码到业务代码。
- 修改行为时优先先写测试，再写实现；测试必须验证可观察行为，不要只验证内部实现细节。
- 保持 UTF-8 编码和现有文件格式。

## Spring AI 规则

- 当前使用 `spring-ai-starter-model-openai`。
- 用例通过应用层定义的外部能力接口调用 AI，例如摘要功能使用 `SummaryGenerator`。基础设施适配依赖 Spring AI 抽象接口，供应商特有实现封装在该边界内；应用层和领域层不直接依赖 `ChatModel`、`ChatClient` 或供应商 SDK。
- 默认配置必须保持 `spring.ai.model.chat=none`，使没有 API Key 的环境可以启动和测试。后续新增依赖模型的接口时，必须明确模型关闭时的错误响应，并保持健康检查和上下文测试正常。
- 需要启用 OpenAI 时使用环境变量：

```text
SPRING_AI_MODEL_CHAT=openai
OPENAI_API_KEY=<secret>
OPENAI_MODEL=<model-name>
```

- API Key、Token、密码、私钥和生产连接串禁止写入源码、配置文件、测试代码和提交记录。
- 不得在普通单元测试或上下文测试中访问真实模型 API、外部网络或个人凭据。
- Prompt 不要直接散落在 Controller 中；可复用或需要版本控制的 Prompt 放在 `src/main/resources/prompts/`。
- 日志禁止输出密钥、完整用户隐私数据、完整 Prompt 或完整模型响应。

## 配置规则

- `src/main/resources/application.yml` 只保留安全默认值和环境变量引用。
- 新增环境变量时，必须同时更新 README 和本文件。
- 不提交 `.env`、本地 IDE 配置和 `target/` 生成物。
- 修改端口、模型开关或启动方式后，必须同步更新 README。

### 基础工程环境变量

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP 端口 |
| `SPRING_AI_MODEL_CHAT` | `none` | 设为 `openai` 启用模型 |
| `OPENAI_API_KEY` | 空 | 模型凭据，通过本地环境提供 |
| `OPENAI_MODEL` | `gpt-5-mini` | 模型名称 |

## 测试与验证

代码变更后至少运行：

```bash
mvn clean verify
```

完成前还应根据改动类型补充验证：

- 修改 Controller：验证路径、HTTP 状态码和响应结构。
- 修改 Spring 配置：运行上下文测试，并确认无 API Key 时仍能启动。
- 修改 AI 配置：确认模型开关、API Key 和模型名称均可通过环境变量覆盖。
- 修改依赖：检查 Maven 依赖解析和最终构建产物。
- 修改启动行为：实际启动应用并请求 `GET /api/health`。

不能访问外部服务或缺少必要凭据时，说明验证边界，不要伪造成功结果。

普通测试通过独立测试配置强制关闭模型并清空 API Key，不依赖外部数据库、Docker、模型服务或个人凭据。

## 文档同步

以下变化必须同步修改 README 和本文件：

- 技术栈或依赖版本变化。
- 模块结构、职责或依赖方向变化。
- 启动命令变化。
- 环境变量变化。
- API 路径或响应变化。
- 测试和构建命令变化。

公开 HTTP 接口文档维护在 `docs/api.md`，README 提供入口。新增或修改请求参数、校验规则、状态码、响应字段或错误码时，同步更新 API 文档；只记录生产源码中实际存在的接口，不把测试 Controller 或内部 Java 合同列为公开接口。

## 代理交付要求

完成任务时，最终说明：

1. 修改了哪些文件，以及每个文件的目的。
2. 执行了哪些验证命令，以及实际结果。
3. 未完成的验证、已知限制或需要用户决策的事项。

不要声称未实际运行的测试、构建或接口验证已经通过。
