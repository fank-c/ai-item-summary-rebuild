# 运行指南

当前应用使用 MySQL 保存用户、文件及知识库元数据。开始前准备 Java 21、Maven 3.9+、MySQL，并将终端或 IDE 的工作目录设为项目根目录。知识库基础目前通过内部 Java 服务调用。

返回 [文档导航](../README.md)。配置变量与默认值见 [配置说明](../reference/configuration.md)。

## 1. 准备数据库

创建 `ai_item_summary` 数据库，使用可在该库执行建表和业务读写的账户，在 MySQL 客户端先选择目标库，再执行脚本：

```sql
USE ai_item_summary;
SOURCE deploy/mysql/schema.sql;
```

也可以通过数据库工具执行 [schema.sql](../../deploy/mysql/schema.sql)。脚本包含 `user`、`file_asset` 以及四张知识库核心表；应用不自动初始化生产库。

`CREATE TABLE IF NOT EXISTS` 只负责首次创建，不能升级旧表。历史数据导入和旧表迁移需要单独处理；导入前确认旧 `DATETIME` 的时区，保留用户 ID、密码摘要和删除状态。

## 2. 创建本地配置

已有 `.env` 时保留内容。文件不存在时，从 [模板](../../.env.example) 创建：

Bash：

```bash
test -e .env || cp .env.example .env
```

PowerShell：

```powershell
if (-not (Test-Path -LiteralPath .env)) {
    Copy-Item -LiteralPath .env.example -Destination .env
}
```

填写 `DB_URL`、`DB_USERNAME` 和 `DB_PASSWORD`。连接 URL 保留 UTC 参数：

```properties
DB_URL=jdbc:mysql://localhost:3306/ai_item_summary?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
```

凭据在本地填写，不写入文档或源码。模型和存储默认关闭；无模型或存储凭据时可以启动，但数据库业务需要可用的 MySQL。[配置说明](../reference/configuration.md) 解释 `.env` 格式、加载优先级和全部变量。

## 3. 启动与检查

```bash
mvn spring-boot:run
```

默认端口为 `8080`，可通过 `SERVER_PORT` 覆盖。Bash 检查健康接口：

```bash
curl -i http://localhost:8080/api/health
```

PowerShell：

```powershell
Invoke-RestMethod -Uri http://localhost:8080/api/health
```

预期 HTTP 状态为 `200`，响应为 `{"status":"UP"}`。该接口返回应用的固定健康响应，不访问数据库、模型或存储；各业务接口还需要单独验证。

默认模拟身份为 `mock-user`。用户 CRUD 不使用该身份做权限判断；文件接口以该身份记录并检查所有者。模拟身份不会创建用户表记录，也不会解析请求中的 Authorization。

## 4. 启用文件存储

按实际环境选择 MinIO 或阿里云 OSS，事先创建私有 Bucket，再填写本地连接配置并启用对应开关。接口中的 `storageType=minio|aliyun` 只能选择已启用的适配器，Bucket 由服务端配置决定。

- MinIO：设置 `MINIO_ENABLED=true`，填写 endpoint、bucket、access key 和 secret key；按部署情况调整 region 和访问方式。
- OSS：设置 `ALIYUN_OSS_ENABLED=true`，填写 endpoint、bucket、access key ID 和 secret。

启用后缺少必填配置会拒绝启动。应用不会自动创建 Bucket。上传、下载地址及删除的调用示例见 [文件接口](../reference/api/files.md)。

默认单文件与整个 multipart 请求都是 `20MB`；请求额外包含边界和表单元数据。需要上传恰好 20 MB 的文件时，适当增大 `APP_FILE_REQUEST_MAX_SIZE`。

## 5. JAR 与 IDE 运行

先执行：

```bash
mvn clean verify
java -jar target/ai-item-summary-0.0.1-SNAPSHOT.jar
```

JAR 启动和 IDE 运行都应使用 Java 21，并把工作目录设为项目根目录。`.env` 从进程工作目录读取，不按 JAR 所在位置查找；JAR 内不包含本地 `.env`。

IDE 中运行 `com.item.aisummary.AiItemSummaryApplication` 即可。配置不存在时应用仍可加载默认值，但用户、文件及知识库元数据操作需要有效数据库连接。

## 后续学习

启用 OpenAI 时提供 `SPRING_AI_MODEL_CHAT=openai`、`OPENAI_API_KEY` 和需要的 `OPENAI_MODEL`。当前用户、文件和知识库基础模块没有模型调用；对话和 RAG 仍待后续实现。

知识库内部调用见 [模块说明](../modules/knowledge.md)，现有四表见 [表结构说明](../reference/database/knowledge.md)。运行验证与真实 MySQL 用户验收见 [测试说明](./testing.md)。
