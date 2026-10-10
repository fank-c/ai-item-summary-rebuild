# 用户模块实现说明

用户模块提供资料 CRUD、BCrypt 密码摘要、逻辑删除，以及跨模块的用户存在性查询。它为后续 AI 应用提供基础用户数据，当前没有登录、Session 或权限功能。

学习目标是理解 HTTP 校验、用例编排、领域数据和持久化适配之间的边界，并看清“省略密码”如何贯穿服务与 SQL。运行步骤见 [运行指南](../guides/getting-started.md)，输入、六字段响应和调用示例见 [用户接口](../reference/api/users.md)，错误码见 [公共约定](../reference/api/common.md#错误码)。返回 [文档导航](../README.md)。

响应只包含 `id/name/email/status/createAt/updateAt`。密码摘要只用于内部处理，不进入 HTTP 响应；记录中的删除时间也不公开。

## 创建与读取

创建链路为：

```text
HTTP 请求校验 → SaveUserCommand → 裁剪名称与邮箱 → 编码原始密码
→ 生成 UTC 时间 → 仓储插入 → UserProfile → UserResponse
```

HTTP 先检查名称、邮箱和声明的密码字符长度，服务随后裁剪普通文本。邮箱大小写保留；名称、邮箱可以重复。新用户 `status=1`。

BCrypt 为密码生成带随机盐的摘要，同样的密码可以得到不同摘要。直接使用 `BCryptPasswordEncoder`，摘要不加 `{bcrypt}` 前缀，已有摘要无需重新编码。密码保持输入原样，首尾空格参与编码；空白拒绝。HTTP 最多允许 255 字符，新密码还必须不超过 72 个 UTF-8 字节，不截断、不预先散列。

单用户操作会裁剪路径 ID。查询由仓储过滤已删除数据，服务也防御性隐藏已删除记录；用户列表没有分页。

## 修改时显式传递密码意图

修改要求提交名称和邮箱。密码省略或为 `null` 时保留；空白会失败；明确提交新密码时重新编码。

关键是 `UserRepository.update(user, passwordChanged)`：

| `passwordChanged` | SQL 更新行为 | 原因 |
| --- | --- | --- |
| `false` | 更新 `name/email/update_at`，不包含 `password` 列 | 资料快照可能持有旧摘要，避免覆盖数据库中较新的密码 |
| `true` | 额外更新 `password` 为新摘要 | 应用明确要求密码变更 |

更新集合不包含 `id/status/create_at/deleted_at`，WHERE 保留 ID 和未删除条件。写入必须影响一行，零行或其他异常行数会作为内部失败处理。

这只保护“未改密码的资料更新”。两个明确密码修改或两个资料修改仍可能互相覆盖；没有版本字段或完整并发冲突处理。响应的状态和创建时间来自服务已读取的快照，不声称返回数据库最新快照。

## 逻辑删除与 UTC

删除保留物理记录，写入数据库生成的 `deleted_at`，刷新 `update_at`，不改变 `status`。已删除用户在详情、更新、删除和引用查询中不可见；重复删除返回 404。

未删除判断与 `status` 无关。禁用状态管理尚未实现，不用 `status` 过滤普通查询或用户引用。

领域类型使用 `Instant`，数据库时间是 UTC `DATETIME(6)`。创建与修改时间保留微秒；删除时间由数据库生成。UTC 类型处理器及连接要求见 [工程结构](../architecture.md#持久化与时间)。

## 跨模块用户引用与模拟身份

[UserReferenceService](../../src/main/java/com/item/aisummary/application/user/reference/UserReferenceService.java) 暴露 `requireExisting(userId)`，返回 `UserReference`。数据库实现只要求用户未删除，不要求 `status=1`；缺失时抛用户未找到异常。模拟实现遇到非配置 ID 时抛 `IllegalArgumentException`。

- 默认模拟模式只接受配置用户 ID，直接返回 `UserReference`，不查询用户表。
- 关闭模拟后使用数据库实现，查询未删除用户。
- 这项能力只回答“用户是否存在”，不能代替认证或权限检查。

用户 CRUD 当前不读取请求身份。关闭 `APP_AUTH_MOCK_ENABLED` 后 CRUD 仍可调用；文件接口则因缺少身份返回 401。模拟身份的配置和请求生命周期见 [工程结构](../architecture.md#请求身份与用户引用)。

## 代码地图

| 位置 | 职责 | 关键源码 |
| --- | --- | --- |
| `web.user.controller`、`request`、`response` | `/api/users` CRUD、请求校验、响应映射 | [UserController](../../src/main/java/com/item/aisummary/web/user/controller/UserController.java)、[CreateUserRequest](../../src/main/java/com/item/aisummary/web/user/request/CreateUserRequest.java)、[UpdateUserRequest](../../src/main/java/com/item/aisummary/web/user/request/UpdateUserRequest.java)、[UserResponse](../../src/main/java/com/item/aisummary/web/user/response/UserResponse.java) |
| `application.user.service`、`command`、`result` | 接收命令，协调文本规则、仓储和摘要，返回资料结果 | [UserService](../../src/main/java/com/item/aisummary/application/user/service/UserService.java)、[SaveUserCommand](../../src/main/java/com/item/aisummary/application/user/command/SaveUserCommand.java)、[UserProfile](../../src/main/java/com/item/aisummary/application/user/result/UserProfile.java) |
| `application.user.port`、`reference` | 仓储、摘要能力与跨模块引用合同 | [UserRepository](../../src/main/java/com/item/aisummary/application/user/port/UserRepository.java)、[PasswordHasher](../../src/main/java/com/item/aisummary/application/user/port/PasswordHasher.java)、[UserReferenceService](../../src/main/java/com/item/aisummary/application/user/reference/UserReferenceService.java) |
| `domain.user.model`、`exception` | 用户业务数据与文本边界、未找到异常 | [User](../../src/main/java/com/item/aisummary/domain/user/model/User.java)、[UserNotFoundException](../../src/main/java/com/item/aisummary/domain/user/exception/UserNotFoundException.java) |
| `infrastructure.user.config`、`persistence.entity / mapper / repository`、`reference` | 用户装配、表映射、SQL 与仓储适配、引用切换 | [UserConfiguration](../../src/main/java/com/item/aisummary/infrastructure/user/config/UserConfiguration.java)、[MyBatisUserRepository](../../src/main/java/com/item/aisummary/infrastructure/user/persistence/repository/MyBatisUserRepository.java) |
| `infrastructure.security`、`persistence.typehandler`、`time` | BCrypt、共享 UTC 转换和时钟 | [BCryptPasswordHasher](../../src/main/java/com/item/aisummary/infrastructure/security/BCryptPasswordHasher.java)、[UtcInstantTypeHandler](../../src/main/java/com/item/aisummary/infrastructure/persistence/typehandler/UtcInstantTypeHandler.java)、[ClockConfiguration](../../src/main/java/com/item/aisummary/infrastructure/time/ClockConfiguration.java) |

## 测试与学习入口

| 测试 | 可观察行为 |
| --- | --- |
| [UserHttpTest](../../src/test/java/com/item/aisummary/web/user/controller/UserHttpTest.java) | CRUD、响应字段、密码空格与长度、逻辑删除和请求标识 |
| [UserServiceTest](../../src/test/java/com/item/aisummary/application/user/service/UserServiceTest.java) | 文本处理、密码更新意图、写入行数和防御性查询 |
| [UserRepositoryTest](../../src/test/java/com/item/aisummary/infrastructure/user/persistence/repository/UserRepositoryTest.java) | H2 上真实 Mapper 的更新列、旧摘要保护与 UTC 微秒 |
| [BCryptPasswordHasherTest](../../src/test/java/com/item/aisummary/infrastructure/security/BCryptPasswordHasherTest.java) | 盐、原始空格、已有摘要兼容性和 UTF-8 字节上限 |
| [UserReferenceConfigurationTest](../../src/test/java/com/item/aisummary/infrastructure/user/reference/UserReferenceConfigurationTest.java) | 模拟与数据库引用切换、身份长度和不按状态过滤 |
| [UserMySqlIT](../../src/test/java/com/item/aisummary/web/user/controller/UserMySqlIT.java) | 显式启用时执行正式 MySQL schema 和完整 HTTP 生命周期 |

推荐先阅读 Controller 和 Service，再对照仓储的更新列及上述测试。全部测试命令和外部验收边界见 [测试说明](../guides/testing.md)。
