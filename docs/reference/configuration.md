# 配置说明

生产配置位于 [application.yml](../../src/main/resources/application.yml)，本地模板位于 [.env.example](../../.env.example)。模板覆盖当前全部运行变量，凭据默认留空。

返回 [文档导航](../README.md)。最短启动与测试步骤见 [项目 README](../../README.md)。

## 本地文件与优先级

应用使用 Spring Boot 原生配置导入：

```yaml
spring:
  config:
    import: optional:file:./.env[.properties][encoding=utf-8]
```

`.env` 从进程工作目录读取，缺少文件不报错。Maven、IDE 和 JAR 通常都以项目根目录为工作目录。

对本项目的配置项，应用命令行参数优先于进程环境变量，进程环境变量优先于 `.env`，变量未定义时才使用 YAML 默认值。空值是明确配置，不会自动回退为默认值。

例如，`.env` 的 `SERVER_PORT=9081` 可被进程环境变量 `SERVER_PORT=9082` 覆盖；应用参数 `--server.port=9083` 又会覆盖前两者。通过 Maven 传入应用参数：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=9083"
```

导入只影响 Spring 的配置读取，不修改操作系统环境或 `System.getenv()`。专用 MySQL 验收读取进程环境变量，不能只把 `USER_TEST_DB_*` 写进 `.env`。

## `.env` 文件格式

使用 UTF-8 Java Properties 格式，不能作为 Shell 脚本执行：

```properties
# 注释单独一行
SERVER_PORT=8080
SPRING_AI_MODEL_CHAT=none
```

- 写 `KEY=value`，不加 `export`，不包引号；引号会成为值的一部分。
- 值中可包含 `#` 和 `=`，不要在值后加行内注释。
- 反斜杠写成 `\\`，需要保留值开头的空格时写成 `\ `。
- Spring 仍会解析 `${...}`，不要把它当作普通的 Shell dotenv 文件处理。
- 不执行 `source .env`，不提交本地 `.env`、`.env.*` 或凭据；仓库只保留 `.env.example`。

创建或同步配置时保留已有本地值，只补缺少的键。示例凭据始终为空。

## HTTP、数据库与 AI

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP 监听端口 |
| `DB_URL` | `jdbc:mysql://localhost:3306/ai_item_summary?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` | 覆盖时保留 UTC 参数 |
| `DB_USERNAME` | 空 | 本地提供数据库账户 |
| `DB_PASSWORD` | 空 | 本地提供数据库密码 |
| `SPRING_AI_MODEL_CHAT` | `none` | `openai` 启用聊天模型；当前没有聊天业务调用 |
| `OPENAI_API_KEY` | 空 | 启用 OpenAI 时提供 |
| `OPENAI_MODEL` | `gpt-5-mini` | OpenAI 模型名称 |

数据库时间使用 UTC。生产 `DATETIME(6)` 通过显式类型处理器映射为 `Instant`，没有内存数据库回退。

## RAG 构建配置

RAG 构建默认关闭，基础元数据与健康接口不需要模型或向量凭据。启用时除 `APP_KNOWLEDGE_RAG_ENABLED=true` 外，还要求 Embedding=`openai`、向量存储=`qdrant`，并在应用中注册至少一个生产解析策略与 FLAT 分块策略；缺少外部后端依赖或配套 Bean 缺失时仍拒绝启动。

解析器装配规则：

- `APP_KNOWLEDGE_RAG_ENABLED=true` 时自动装配本地生产解析策略：纯文本（TXT）、Markdown 和 Office Word（DOCX，基于 Apache POI 5.4.1）。解析过程均为本地纯 Java 处理，无外部网络/模型调用，构造参数复用 `maxSourceSize`（绑定 `APP_FILE_MAX_SIZE`）。
- 仅当 `APP_KNOWLEDGE_RAG_ENABLED=true` 且 `APP_KNOWLEDGE_DOCMIND_ENABLED=true` 时，装配基于阿里云文档智能的 PDF 云端解析策略 Bean（`AlibabaPdfKnowledgeDocumentParser` / `DocMindPdfParserClient`）。未显式启用时不会上传任何文件至云端；启用后上传 PDF 进行云端解析会消耗服务额度并可能产生费用，且启动时必须配置 AccessKey ID 与 Secret。

变量通过主配置映射到 Spring AI 2.0.1 的 Embedding 与 Qdrant 属性。硅基流动可按 OpenAI 兼容接口使用 `OPENAI_EMBEDDING_BASE_URL=https://api.siliconflow.cn/v1`、`OPENAI_EMBEDDING_MODEL=BAAI/bge-m3`，并填写 `OPENAI_EMBEDDING_API_KEY`。该模型输出 1024 维，保持 `OPENAI_EMBEDDING_DIMENSIONS` 为空；Qdrant 集合也必须为 1024 维。聊天仍可保持关闭。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APP_KNOWLEDGE_RAG_ENABLED` | `false` | 是否装配内部构建服务；关闭时该服务 Bean 不存在 |
| `SPRING_AI_MODEL_EMBEDDING` | `none` | 启用时为 `openai` |
| `SPRING_AI_VECTORSTORE_TYPE` | `none` | 启用时为 `qdrant`；关闭防止默认创建向量后端 |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | OpenAI 兼容聊天服务的公共 base URL |
| `OPENAI_EMBEDDING_API_KEY` | 空 | Embedding 专用凭据，未提供有效值时模型可沿用公共 `OPENAI_API_KEY` |
| `OPENAI_EMBEDDING_BASE_URL` | 空 | Embedding 专用 base URL；未设置时沿用 `OPENAI_BASE_URL` |
| `OPENAI_EMBEDDING_MODEL` | `text-embedding-3-small` | Embedding 模型，独立于聊天模型；SiliconFlow 示例为 `BAAI/bge-m3` |
| `OPENAI_EMBEDDING_DIMENSIONS` | 空 | 可选正整数，空时由模型推断，结果必须与集合维度匹配。SiliconFlow `BAAI/bge-m3` 输出 1024 维且不支持此请求参数，应保持为空 |
| `QDRANT_HOST` | `localhost` | Qdrant 主机，按实际服务覆盖 |
| `QDRANT_PORT` | `6334` | gRPC 端口 |
| `QDRANT_USE_TLS` | `false` | 使用 TLS，托管服务通常需启用 |
| `QDRANT_API_KEY` | 空 | 按 Qdrant 部署要求本地提供 |
| `QDRANT_COLLECTION_NAME` | `knowledge_chunks` | 向量集合；验收使用独立集合 |
| `QDRANT_INITIALIZE_SCHEMA` | `false` | true 时创建缺失集合，不迁移已有集合维度 |
| `RAG_BATCH_SIZE` | `128` | 范围 1～1024；数据库保存的目标组批大小、不拆分组；向量写入使用该精确批量上限，尾批可不足 |
| `RAG_CHUNK_SIZE` | `512` | 正整数，子检索块目标尺寸上限（Token 单位，CL100K_BASE 计数） |
| `RAG_PARENT_CHUNK_SIZE` | `2048` | 正整数，父上下文块目标尺寸上限（Token 单位，必须 `>= RAG_CHUNK_SIZE`） |
| `RAG_CHUNK_OVERLAP` | `0` | 非负整数，子检索块重叠目标尺寸（Token 单位，必须满足 `0 <= RAG_CHUNK_OVERLAP < RAG_CHUNK_SIZE`） |
| `RAG_MIN_CHUNK_SIZE_CHARS` | `200` | 非负，标点断句的短块软阈值（字符数），低于此阈值尽量不切断，但不丢弃短尾 |
| `RAG_MIN_CHUNK_LENGTH_TO_EMBED` | `0` | 当前只允许 0，作为策略选项；编排不据此过滤短块 |
| `RAG_MAX_NUM_CHUNKS` | `100000` | 正整数，限制一个构建的全部角色片段总数（CONTEXT 与 RETRIEVAL 均计入） |
| `RAG_KEEP_SEPARATOR` | `true` | 是否原样保留换行；`false` 仅普通文本 CR/LF 一对一替换为空格（结构块始终保留换行），不删标点不 trim |
| `RAG_PUNCTUATION_MARKS` | `.?!;。？！；` 与换行 | 非空字符串，传递给策略；Properties 中 `\n` 表示换行 |

原始源文件大小上限复用 `APP_FILE_MAX_SIZE`，默认 20 MB，必须为正且小于 Integer.MAX_VALUE 字节。`KnowledgeSourceReader` 会将限量原字节预读到内存并校验大小和 SHA-256，不执行 UTF-8 解码；生产 TXT 与 Markdown 解析器委托 `Utf8TextDecoder` 负责严格 UTF-8 校验与换行符规整。

切分模式由内部 `build` 调用的第三参数选择，不对应环境变量。两参数入口默认 FLAT。构建合同与失败边界见 [知识库模块](../modules/knowledge.md)。

## 阿里云文档智能（Document Mind）

PDF 解析基于阿里云文档智能的大模型文档解析服务（`docmind_api20220711:2.0.14`）结合 Apache PDFBox 3.0.5 本地前置校验实现。官方接口规范见 [阿里云文档智能大模型版本文档解析](https://help.aliyun.com/zh/document-mind/developer-reference/document-parsing-large-model-version)。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APP_KNOWLEDGE_DOCMIND_ENABLED` | `false` | 是否启用 PDF 云端解析；仅在 `APP_KNOWLEDGE_RAG_ENABLED=true` 时生效。启用后处理 PDF 会上传至云端并消耗服务额度/可能产生费用 |
| `ALIYUN_DOCMIND_ACCESS_KEY_ID` | 空 | 启用时必填的阿里云 AccessKey ID |
| `ALIYUN_DOCMIND_ACCESS_KEY_SECRET` | 空 | 启用时必填的阿里云 AccessKey Secret |
| `ALIYUN_DOCMIND_ENDPOINT` | `docmind-api.cn-hangzhou.aliyuncs.com` | 文档智能服务接入点 |
| `ALIYUN_DOCMIND_VLM_ENHANCEMENT` | `true` | 是否启用视觉大模型增强（VLM）；开启后可能增加单页解析费用与处理耗时 |
| `ALIYUN_DOCMIND_FORMULA_ENHANCEMENT` | `false` | 是否启用公式识别增强 |
| `ALIYUN_DOCMIND_WAIT_TIMEOUT` | `600s` | 提交、轮询与结果拉取共用的等待时限，范围 `1ms`～`1h`；不保证同步 SDK 硬实时取消 |
| `ALIYUN_DOCMIND_POLL_INTERVAL` | `2s` | 云端解析任务状态轮询间隔，范围 `1ms`～`1min` |

### 通信与调用约定

- **云端上传**：启用后 PDF 将发送到阿里云进行 OCR 与版面分析；解析流程见 [知识库模块](../modules/knowledge.md#pdf-解析与云端网关)。
- **重试与超时**：禁用 SDK 自动重试。单次 HTTP 连接超时最多 5 秒、读取最多 30 秒，并根据提交、轮询与结果拉取共用的剩余等待预算收缩。底层同步 SDK 无法保证硬实时取消或远程任务撤回；超时或应用进程重启不恢复云端任务，重新提交可能重复计费。
- **前置本地防御**：在调用云端前，先通过 Apache PDFBox 3.0.5 进行本地预检，校验 PDF 容器完整性、拒绝加密文档，并限制页数不超过 2,000 页，避免无效或超大文件产生云端调用。

## 模拟身份

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APP_AUTH_MOCK_ENABLED` | `true` | 向 `/api/**` 的非 OPTIONS 请求注入模拟身份 |
| `APP_AUTH_MOCK_USER_ID` | `mock-user` | 启用时 trim 后非空，最多 64 个 Unicode 码点 |
| `APP_AUTH_MOCK_USERNAME` | `mock-user` | 模拟身份的名称 |

Unicode 码点指一个完整 Unicode 字符，补充平面字符计为 1。模拟配置裁剪 ID；文件服务的内部显式 ID 不裁剪，最多同样是 64 个码点。关闭模拟后不校验未使用的配置 ID 长度。

模拟模式的用户引用只接受配置 ID，不查数据库；关闭后查询未删除用户。引用查询不等于认证。用户 CRUD 当前没有身份或权限判断；文件接口在缺少身份时返回 401。

## 上传与共享存储参数

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APP_FILE_MAX_SIZE` | `20MB` | multipart 单文件限制及业务实际字节上限 |
| `APP_FILE_REQUEST_MAX_SIZE` | `20MB` | 整个 multipart 请求上限，包含边界和元数据 |
| `APP_FILE_MAX_PART_HEADER_SIZE` | `4KB` | Tomcat 单个 multipart part 的头部字节上限，映射 `server.tomcat.max-part-header-size`，容纳合法 UTF-8 长文件名；超限仍返回 413 |
| `FILE_STORAGE_KEY_PREFIX` | `files` | 规范化后 1～150 字符；参与 Key 的 UTF-8 总预算 |
| `FILE_STORAGE_DOWNLOAD_URL_EXPIRATION` | `15m` | 整数秒，范围 `1s`～`7d` |
| `FILE_STORAGE_CONNECT_TIMEOUT` | `5s` | SDK 连接超时；OSS 获取连接等待也用此值 |
| `FILE_STORAGE_READ_TIMEOUT` | `60s` | SDK 读取超时 |
| `FILE_STORAGE_WRITE_TIMEOUT` | `60s` | MinIO HTTP 写入超时；OSS 没有独立写入超时配置 |

扩展名白名单当前在主配置中固定为 `txt`、`md`、`pdf`、`docx`，没有对应环境变量。校验不识别真实文件格式。

MinIO 新 Key 使用 `{prefix}/{yyyy/MM/dd}/{UUID}.{小写扩展名}`；OSS 在此前叠加项目目录，默认是 `item/ai-item-summary/{prefix}/{yyyy/MM/dd}/{UUID}.{小写扩展名}`。公共 `{prefix}` 取 `FILE_STORAGE_KEY_PREFIX`（默认 `files`），OSS 项目目录取 `ALIYUN_OSS_KEY_PREFIX`，去掉首尾斜杠后拼接，完整 Key 不以 `/` 开头并保存到数据库。原始展示名称完整保存在元数据中，不进入 Key，也不截断。新 Key 同时限制 `String.length()` 不超过 512、UTF-8 总字节不超过 1023、每个 `/` 分隔的路径段不超过 255 个 UTF-8 字节。长多字节前缀需逐段符合预算，超限在首次读流前拒绝；历史 Key 仍按记录值下载、删除与读取，不重新校验。

multipart 的单文件、整个请求和单个 part 头部是三种独立限制。默认 4KB part 头部预算兼顾合法长文件名与 MIME；减小该值可能让文件名尚未进入业务校验就返回 413，413 不仅表示文件正文超限。`APP_FILE_MAX_PART_HEADER_SIZE` 应保留正的有限大小，不以关闭限制替代文件名校验。下载 URL 的末段是 UUID 与扩展名，客户端需使用上传响应的 `originName` 显示或保存原始名称；当前不额外设置下载 Content-Disposition。

SDK 超时须为正整数毫秒，且不超过 `Integer.MAX_VALUE` 毫秒；不承诺整个流处理的总时长。

## MinIO

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MINIO_ENABLED` | `false` | 是否启用 |
| `MINIO_ENDPOINT` | 空 | 启用时必填的 HTTP / HTTPS 服务地址 |
| `MINIO_BUCKET` | 空 | 启用时必填，Bucket 事先创建 |
| `MINIO_REGION` | `us-east-1` | 按实际部署区域调整 |
| `MINIO_ACCESS_KEY` | 空 | 启用时必填 |
| `MINIO_SECRET_KEY` | 空 | 启用时必填 |
| `MINIO_PATH_STYLE_ACCESS` | `true` | 是否使用路径式 Bucket 访问 |

## 阿里云 OSS

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `ALIYUN_OSS_ENABLED` | `false` | 是否启用 |
| `ALIYUN_OSS_KEY_PREFIX` | `item/${spring.application.name}`（当前 `item/ai-item-summary`） | 仅新上传使用的 OSS 项目目录；叠加在公共前缀前，启用时规范化后 1～150 字符，完整 Key 仍受字符与字节预算约束 |
| `ALIYUN_OSS_ENDPOINT` | 空 | 启用时必填的 HTTP / HTTPS 服务地址 |
| `ALIYUN_OSS_BUCKET` | 空 | 启用时必填，Bucket 事先创建 |
| `ALIYUN_OSS_ACCESS_KEY_ID` | 空 | 启用时必填 |
| `ALIYUN_OSS_ACCESS_KEY_SECRET` | 空 | 启用时必填 |

`ALIYUN_OSS_KEY_PREFIX` 未显式设置时随 Spring 应用名称变化；例如公共前缀是 `files` 时，新 OSS Key 为 `item/ai-item-summary/files/年/月/日/UUID.扩展名`。项目目录只在上传时叠加，修改配置不会搬迁旧对象，旧文件仍按已存 Key 下载、读取和删除。

两个厂商默认关闭；选择未启用的类型不会自动回退。启用时缺少 endpoint、bucket 或凭据会拒绝启动。配置校验不检查真实凭据是否可用，也不创建 Bucket。

## 专用 MySQL 测试变量

以下三个变量只用于 `mvn -Pmysql-it verify`，不是运行变量的一部分。模板只把它们列为注释参考。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `USER_TEST_DB_URL` | 无 | 独立测试库名以 `_test` 结尾，保留 UTC 参数 |
| `USER_TEST_DB_USERNAME` | 无 | 具有测试库建表与 CRUD 权限的账户 |
| `USER_TEST_DB_PASSWORD` | 空 | 本地提供 |

通过进程环境变量提供这些值，再执行 `mvn -Pmysql-it verify`；只写入 `.env` 不生效。该 profile 在指定测试库执行正式 schema 和用户 HTTP CRUD，并在结束时清理本次创建的用户记录。它只覆盖用户模块，不代表知识库 MySQL 或外部模型、向量库、对象存储及云端解析已验收。

普通测试使用 [测试配置](../../src/test/resources/application.yml)，不导入 `.env`，模型、Qdrant、RAG 构建及外部对象存储保持关闭。
