# 配置说明

生产配置位于 [application.yml](../../src/main/resources/application.yml)，本地模板位于 [.env.example](../../.env.example)。模板覆盖当前全部 29 个运行变量，凭据默认留空。

返回 [文档导航](../README.md)。创建本地配置与启动步骤见 [运行指南](../guides/getting-started.md)。

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
| `SPRING_AI_MODEL_CHAT` | `none` | `openai` 启用模型；当前用户、文件和知识库基础模块不调用模型 |
| `OPENAI_API_KEY` | 空 | 启用 OpenAI 时提供 |
| `OPENAI_MODEL` | `gpt-5-mini` | OpenAI 模型名称 |

数据库时间使用 UTC。生产 `DATETIME(6)` 通过显式类型处理器映射为 `Instant`，没有内存数据库回退。

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
| `FILE_STORAGE_KEY_PREFIX` | `files` | 规范化后 1～150 字符；参与 Key 的 UTF-8 总预算 |
| `FILE_STORAGE_DOWNLOAD_URL_EXPIRATION` | `15m` | 整数秒，范围 `1s`～`7d` |
| `FILE_STORAGE_CONNECT_TIMEOUT` | `5s` | SDK 连接超时；OSS 获取连接等待也用此值 |
| `FILE_STORAGE_READ_TIMEOUT` | `60s` | SDK 读取超时 |
| `FILE_STORAGE_WRITE_TIMEOUT` | `60s` | MinIO HTTP 写入超时；OSS 没有独立写入超时配置 |

扩展名白名单当前在主配置中固定为 `txt`、`md`、`pdf`、`docx`，没有对应环境变量。校验不识别真实文件格式。

生成的新 Key 同时限制 `String.length()` 不超过 512 和 UTF-8 不超过 1023 字节。多字节前缀与文件名共同占用预算，不截断名称。SDK 超时须为正整数毫秒，且不超过 `Integer.MAX_VALUE` 毫秒；不承诺整个流处理的总时长。

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
| `ALIYUN_OSS_ENDPOINT` | 空 | 启用时必填的 HTTP / HTTPS 服务地址 |
| `ALIYUN_OSS_BUCKET` | 空 | 启用时必填，Bucket 事先创建 |
| `ALIYUN_OSS_ACCESS_KEY_ID` | 空 | 启用时必填 |
| `ALIYUN_OSS_ACCESS_KEY_SECRET` | 空 | 启用时必填 |

两个厂商默认关闭；选择未启用的类型不会自动回退。启用时缺少 endpoint、bucket 或凭据会拒绝启动。配置校验不检查真实凭据是否可用，也不创建 Bucket。

## 专用 MySQL 测试变量

以下三个变量只用于 `mvn -Pmysql-it verify`，不是上述 29 个运行变量的一部分。模板只把它们列为注释参考。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `USER_TEST_DB_URL` | 无 | 独立测试库名以 `_test` 结尾，保留 UTC 参数 |
| `USER_TEST_DB_USERNAME` | 无 | 具有测试库建表与 CRUD 权限的账户 |
| `USER_TEST_DB_PASSWORD` | 空 | 本地提供 |

设置方式、清理范围和验收边界见 [测试说明](../guides/testing.md#真实-mysql-用户验收)。

普通测试使用 [测试配置](../../src/test/resources/application.yml)，不导入 `.env`，模型及外部存储保持关闭。
