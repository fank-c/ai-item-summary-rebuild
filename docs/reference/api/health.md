# 健康检查

返回 [接口总览](./README.md)。请求标识与响应格式见 [公共约定](./common.md)。

## GET `/api/health`

不要求身份，无路径参数、查询参数或请求体。

```bash
curl -i http://localhost:8080/api/health
```

PowerShell：

```powershell
Invoke-RestMethod -Uri http://localhost:8080/api/health
```

成功状态：`200 OK`。

```json
{"status":"UP"}
```

该接口返回应用的固定健康响应，不执行数据库、模型服务或对象存储探测。`UP` 只说明 HTTP 服务可达，不代表外部依赖已验证可用。启动步骤见 [运行指南](../../guides/getting-started.md)。

## 源码与测试入口

- [HealthController](../../../src/main/java/com/item/aisummary/web/health/HealthController.java)：接口路径和固定响应。
- [HealthControllerTest](../../../src/test/java/com/item/aisummary/web/health/HealthControllerTest.java)：健康响应测试。
