# API 文档

本文描述基础工程当前已实现的公开 HTTP 接口。服务启动方式和环境变量见 [README](../README.md#启动项目)。

## 调用约定

- 默认服务地址为 `http://localhost:8080`，端口可通过 `SERVER_PORT` 覆盖。
- 当前只提供健康检查，成功响应使用 `application/json`。
- 模型默认关闭，基础工程无需数据库或 API Key 即可启动。

## 健康检查

### GET `/api/health`

无路径参数、查询参数或请求体。

Bash：

```bash
curl -i http://localhost:8080/api/health
```

PowerShell：

```powershell
Invoke-RestMethod -Method Get -Uri 'http://localhost:8080/api/health'
```

成功状态：`200 OK`。

```json
{"status":"UP"}
```

| 字段 | JSON 类型 | 说明 |
| --- | --- | --- |
| `status` | string | 固定返回 `UP`，表示请求已到达健康检查接口 |

该接口不探测数据库、模型服务或其他外部依赖，`UP` 不代表这些外部依赖已验证可用。

## 实现依据与维护

- [HealthController](../src/main/java/com/item/aisummary/web/HealthController.java)：接口路径与响应定义。
- [HealthControllerTest](../src/test/java/com/item/aisummary/web/HealthControllerTest.java)：健康检查方法的响应测试。

新增或修改公开接口时，同步维护本文件和 [README](../README.md)，并按 [项目规则](../AGENTS.md) 验证路径、状态码和响应结构。
