# AI Item Summary

Java 21、Spring Boot 4.1.1、Spring AI 2.0.1 学习工程。当前实现用户、文件和知识库基础能力；知识库支持 TXT/Markdown/DOCX 本地解析、可选阿里云文档智能 PDF 解析、TokenBudgetKnowledgeChunker（FLAT 与 PARENT_CHILD）及 RAG 构建骨架。

RAG、聊天模型、Embedding、Qdrant 和对象存储默认关闭。启用 RAG 需要配置对应外部依赖；PDF 云端解析需要显式启用，并会调用阿里云服务。外部模型、Qdrant、生产 MySQL 和真实云端 PDF 调用尚未完成端到端验收。

## 启动

需要 Java 21、Maven 3.9+ 和 MySQL。工作目录使用项目根目录。

1. 创建数据库并执行 `deploy/mysql/schema.sql`。脚本不会自动升级已有表。
2. 复制 `.env.example` 为 `.env`，填写数据库连接凭据。
3. 运行 `mvn spring-boot:run`。
4. 检查 `GET http://localhost:8080/api/health`。

## 验证

```bash
mvn clean verify
```

普通测试使用 H2 和外部服务替身，不访问真实模型、Qdrant、对象存储或云端解析。真实 MySQL 用户验收使用 `mvn -Pmysql-it verify`，需设置专用测试库的进程环境变量。

## 项目入口

模块与参考资料见 [文档导航](./docs/README.md)，公开接口见 [HTTP API](./docs/reference/api.md)，代理开发规则见 [AGENTS.md](./AGENTS.md)。运行配置模板见 [`.env.example`](./.env.example)，生产数据库初始化脚本见 [`deploy/mysql/schema.sql`](./deploy/mysql/schema.sql)。
