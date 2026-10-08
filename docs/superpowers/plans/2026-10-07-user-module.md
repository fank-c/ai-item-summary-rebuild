# 基础用户模块实现计划与执行记录

> **状态：已完成。** 用户确认设计并要求实施后，按 executing-plans 在当前会话直接执行。未派遣子代理；目录无 Git 元数据，未创建 worktree 或提交。

**目标：** 构建兼容 `/api/users` 的用户资料管理、密码摘要、用户引用与模拟身份。

**架构：** web 映射 HTTP，application 通过仓储和密码接口编排用例，domain 表达用户数据与文本规则。infrastructure 使用 MyBatis-Plus/MySQL、BCrypt 和请求属性身份。

**技术栈：** Java 21、Spring Boot 4.1.1、Spring AI 2.0.1、MyBatis-Plus 3.5.17；H2 仅用于测试。

**规格：** [已确认的设计](../specs/2026-10-07-user-module-design.md)。

## 全局约束

- `/api/users`、六字段响应、逻辑删除和五类 ProblemDetail 错误保持迁移合同。
- 密码不裁剪、回显或输出到日志；新密码最多 72 个 UTF-8 字节，HTTP 声明上限仍为 255 字符。
- 模拟身份与用户表独立；关闭模拟不保护 CRUD；用户引用不按 status 过滤。
- 普通测试不连接外部数据库或模型；生产模型默认关闭。
- 不修改旧工程，不搬运历史数据，不增加权限、分页、邮箱唯一约束或关联清理。

## 文件与职责

| 文件 | 职责 |
| --- | --- |
| `pom.xml` | Validation、MP Boot4、MySQL、Crypto、MVC test、H2 test 依赖，以及显式 mysql-it profile |
| `src/main/resources/application.yml` | 安全默认值和环境变量配置 |
| `deploy/mysql/schema.sql` | 正式 user 表结构，手动执行 |
| `domain/user/User.java`、`UserNotFoundException.java` | 无框架依赖的用户数据、文本规则及不存在异常 |
| `application/user/SaveUserCommand.java`、`UserProfile.java`、`UserService.java` | CRUD 输入、六字段资料结果及用例编排 |
| `application/user/port/UserRepository.java`、`PasswordHasher.java` | 持久化与密码能力合同 |
| `infrastructure/user/persistence/UserEntity.java`、`UserMapper.java`、`MyBatisUserRepository.java` | 数据库映射和逻辑删除 |
| `infrastructure/user/persistence/UtcInstantTypeHandler.java` | 显式 UTC DATETIME(6) 与 Instant 转换 |
| `infrastructure/user/UserConfiguration.java` | 服务及 UTC Clock 装配 |
| `infrastructure/security/BCryptPasswordHasher.java` | 原样密码编码与 UTF-8 字节边界检查 |
| `web/user/CreateUserRequest.java`、`UpdateUserRequest.java`、`UserResponse.java`、`UserController.java` | HTTP 合同 |
| `web/error/GlobalExceptionHandler.java`、`infrastructure/web/RequestIdFilter.java` | 错误映射与请求标识 |
| `application/user/reference/UserReference.java`、`UserReferenceService.java` | 跨模块引用合同 |
| `infrastructure/user/reference/MockUserReferenceService.java`、`DatabaseUserReferenceService.java`、`UserReferenceConfiguration.java` | 引用模式切换 |
| `application/auth/CurrentUser.java`、`AuthenticationRequiredException.java` | 最小身份合同及缺少身份异常 |
| `infrastructure/auth/MockAuthenticationProperties.java`、`LoginUserContext.java`、`MockAuthenticationInterceptor.java`、`MockAuthenticationConfiguration.java` | 请求属性身份 |
| 对应测试包、`src/test/resources/application.yml`、`schema.sql` | 行为测试、隔离 H2 配置和测试表结构 |
| `src/test/java/com/item/aisummary/web/user/UserMySqlIT.java` | 专用真实 MySQL 验收；普通 verify 不执行 |
| `README.md`、`AGENTS.md` | 运行、输入输出、学习目标、配置和验证 |

上表省略前缀的 Java 路径相对于 `src/main/java/com/item/aisummary/`，普通测试位于对应 `src/test/java/com/item/aisummary/`。

## 任务 1：HTTP 用户 CRUD、摘要与持久化适配

- [x] 增加已核对坐标的依赖及测试数据库配置，编写 UserHttpTest。
- [x] 运行 `mvn -Dtest=UserHttpTest test`，观察缺少用户路由导致的预期失败。
- [x] 实现领域数据、应用服务、仓储与密码接口、BCrypt 和 MP 适配、服务装配、HTTP 和公共错误、请求标识、schema。
- [x] 重跑 HTTP 测试，19 项通过，覆盖完整 CRUD、物理记录、密码保留/替换、字段校验、错误结构和 72/73 字节。
- [x] 补充 UserServiceTest、BCryptPasswordHasherTest、UserRepositoryTest、UserFailureHttpTest，验证已实现的失败处理、历史摘要兼容及 UTC 往返。

创建将一次 UTC 应用时钟截断为微秒，并同时赋给创建与更新时间；MyBatis-Plus 生成 32 字符 UUID。插入、修改和删除都要求影响一行。删除时间由 MySQL `CURRENT_TIMESTAMP(6)` 生成，更新集合不含账户状态或个人资料。

## 任务 2：用户引用切换

- [x] 用 ContextRunner 测试唯一引用 Bean，观察缺少 Bean 的预期失败。
- [x] 定义应用层引用接口和 record，实现固定引用与数据库用户引用，按配置条件装配。
- [x] 验证模拟模式不访问仓储，真实模式拒绝删除/不存在用户并接受 status=0。
- [x] 补充开启模拟时空白配置导致启动失败、关闭模拟时不依赖固定身份值的测试。最终该测试类 3 项通过。

## 任务 3：请求属性模拟身份

- [x] 编写开启/关闭模式的 Web 测试，用测试专用 Controller 读取身份。
- [x] 观察缺少注入/401 映射的预期失败，再实现配置、上下文、拦截器及注册。
- [x] 5 项测试通过，覆盖 OPTIONS、非 API 路径、成功/异常清理、无 Session、关闭模式 401，以及关闭后 CRUD 仍可调用。

## 任务 4：文档与交付验证

- [x] 同步 README 和 AGENTS 的模块、配置、手动建表、HTTP 示例、密码边界及验证范围。
- [x] 最终执行 `mvn clean verify`：45 项测试，失败 0，错误 0，跳过 0；BUILD SUCCESS。
- [x] 核对最终依赖树和打包产物。
- [x] 增加显式 `mysql-it` profile 和专用测试库保护，执行真实 MySQL 验收。
- [x] 实际启动打包后的 jar，连接临时 MySQL，关闭模型及模拟身份；完成健康检查和真实 HTTP CRUD。
- [x] 按代码审查检查要点自行核对分层、敏感字段、日志与工作区范围；未执行独立子代理审查。
- [x] 更新规格及本执行记录；停止临时进程并清理临时数据目录。

## 验证证据

以下结果均来自实际命令，日期为 2026-10-07。

| 命令或动作 | 实际结果 |
| --- | --- |
| `mvn -Dtest=UserHttpTest test` | 完整实现后 19 项通过 |
| `mvn -Dtest=UserReferenceConfigurationTest test` | 初始 2 项通过；新增配置边界后随最终构建共 3 项通过 |
| `mvn -Dtest=MockAuthenticationWebTest,MockAuthenticationDisabledWebTest test` | 5 项通过 |
| `mvn -Dtest=UserServiceTest,BCryptPasswordHasherTest,UserRepositoryTest,UserFailureHttpTest test` | 16 项通过 |
| 设置专用 USER_TEST_DB_* 后 `mvn -Pmysql-it verify` | 当次普通测试全部通过，MySQL 集成测试 1 项通过，BUILD SUCCESS |
| 最终 `mvn clean verify` | 45 项普通测试全部通过，BUILD SUCCESS；耗时约 20 秒 |
| `mvn dependency:tree`（过滤相关依赖） | Boot 4.1.1、AI 2.0.1、MP 3.5.17、Crypto 7.1.1、Connector/J 9.7.0；H2 2.4.240 为 test scope |
| `java -jar target/ai-item-summary-0.0.1-SNAPSHOT.jar ...`，Java 21.0.7 | 实际启动，模型关闭，连接专用 MySQL 测试库，模拟身份关闭 |
| 实际 HTTP 请求 | 健康检查 200；创建 201；详情/修改 200；删除 204；删除后详情 404 |
| 实际 MySQL 物理记录查询 | status=1 保留，deleted_at 非空，password 长度为 60；随后清理该测试记录 |
| `jar tf` 检查 | 用户 Controller、MP、MySQL、Crypto 已打包；H2 没有进入运行产物 |
| 源码依赖与日志检查 | application 不依赖 web/infrastructure；domain 无框架引用；错误日志不输出异常详情或密码 |

最终产物：`target/ai-item-summary-0.0.1-SNAPSHOT.jar`。

最终 jar SHA-256：`BF835D5C16396B4117F2119D41134563845FD4BF61B2D703D4C59FB592A51D5D`。

最终普通构建日志位于 `target/user-final-verify.log`，测试详情位于 `target/surefire-reports/`，依赖树位于 `target/user-dependencies.txt`。这些均为生成物，后续 clean 会删除。

MySQL 验收使用本机 mysqld 8.0.31、任务专用临时数据目录和回环地址独立端口，执行正式 `deploy/mysql/schema.sql`。测试核实 UTC 会话、微秒往返、重复邮箱、随机盐、旧摘要保留/替换、逻辑删除、列表过滤和非 1 状态保留。临时 MySQL、应用进程与数据目录已清理，原 MySQL80 服务仍为停止状态。

## 验证边界与保留限制

- 未连接或搬运旧数据库数据；历史 DATETIME 原时区仍需在数据导入前确认，不能仅根据旧 JDBC 默认 Asia/Shanghai 判断。
- 真实 MySQL 验收使用 8.0.31 的专用临时实例，不代表已检查用户日常数据库的账户权限或既有表结构。
- 没有完整认证/权限和并发冲突治理，用户 CRUD 保持本阶段的开放行为。
- jdtls 未安装，Java LSP 诊断不可用；实际 Java 编译、测试和运行均由 Maven/JDK 验证。
- 目录无 Git 元数据，未执行 Git 提交或提交差异检查。
