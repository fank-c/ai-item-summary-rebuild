package com.item.aisummary.application.user.service;

import com.item.aisummary.application.user.command.SaveUserCommand;
import com.item.aisummary.application.user.port.PasswordHasher;
import com.item.aisummary.application.user.port.UserRepository;
import com.item.aisummary.application.user.result.UserProfile;
import com.item.aisummary.domain.user.exception.UserNotFoundException;
import com.item.aisummary.domain.user.model.User;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 用户资料用例；只通过应用接口访问持久化及密码摘要，不承担认证职责。 */
public final class UserService {
    private final UserRepository repository;
    private final PasswordHasher hasher;
    private final Clock clock;

    public UserService(UserRepository repository, PasswordHasher hasher, Clock clock) {
        this.repository = repository;
        this.hasher = hasher;
        this.clock = clock;
    }

    /**
     * 创建用户资料，将普通文本规范化与密码摘要分开处理。
     *
     * <p>先校验并裁剪名称和邮箱，再将未经裁剪的密码交给摘要接口。 名称、邮箱的 HTTP 格式和声明长度校验不由本服务替代；密码合法性由摘要适配器执行。 新记录
     * status=1、未删除，创建与更新时间共用注入时钟产生的一个微秒时间。
     *
     * <p>服务将尚未分配 ID 的用户交给仓储插入，返回仓储保存后的公开资料。 不执行认证或权限检查，不将密码摘要与删除时间暴露给调用方。
     *
     * @param command 名称、邮箱和必填原始密码；密码首尾空格保留
     * @return 仓储保存后的六字段用户资料
     * @throws IllegalArgumentException 名称、邮箱为空白，或摘要适配器拒绝密码
     */
    public UserProfile create(SaveUserCommand command) {
        String name = User.requireText(command.name(), "name");
        String email = User.requireText(command.email(), "email");
        String hash = hasher.encode(command.password());
        Instant now = now();
        User saved = repository.insert(new User(null, name, email, hash, 1, null, now, now));
        return UserProfile.from(saved);
    }

    /**
     * 查询单个未逻辑删除用户，并转换为公开资料。
     *
     * <p>ID 经最小文本规则校验并裁剪；即使仓储意外返回已删除记录，服务也会拒绝访问。 是否未删除只看 deletedAt，不按账户 status 过滤，也不检查请求身份或权限。
     *
     * @param id 用户 ID，不能为空白
     * @return 不含密码摘要或删除时间的用户资料
     * @throws IllegalArgumentException ID 缺失或为空白
     * @throws UserNotFoundException 用户不存在或已经逻辑删除
     */
    public UserProfile findById(String id) {
        return UserProfile.from(findExisting(id));
    }

    /**
     * 查询全部未逻辑删除用户，并按仓储返回顺序映射公开资料。
     *
     * <p>在仓储结果上再次过滤 deletedAt，避免错误适配器暴露已删除记录。 不按账户 status 过滤，不增加分页、排序或权限条件，结果不含密码摘要与删除时间。
     *
     * @return 不可修改的公开资料列表；没有可见用户时为空列表
     */
    public List<UserProfile> findAll() {
        return repository.findAll().stream()
                .filter(user -> user.deletedAt() == null)
                .map(UserProfile::from)
                .toList();
    }

    /**
     * 修改未删除用户的名称、邮箱，并显式传递是否变更密码的意图。
     *
     * <p>先读取用户快照，再校验并裁剪普通文本。密码为 null 时保留快照摘要， 同时向仓储传递 passwordChanged=false，使 SQL 不写 password
     * 列，避免覆盖较新的摘要。 明确提交密码时原样编码并传递 true；空白或过长密码仍由摘要适配器拒绝。
     *
     * <p>只请求更新资料、微秒精度更新时间及可选密码，保留 ID、状态和创建时间。 写入必须影响一行；响应使用本次构造的快照，不重新读取并发变更后的记录。
     * 本规则不解决两个显式密码修改或两个资料修改之间的覆盖。
     *
     * @param id 待修改用户 ID，不能为空白，查询前裁剪
     * @param command 名称和邮箱必填；密码为 null 表示保留，非 null 表示明确更新
     * @return 本次更新快照的公开资料，不含密码摘要
     * @throws IllegalArgumentException ID、普通文本不合法，或摘要适配器拒绝新密码
     * @throws UserNotFoundException 用户不存在或已经逻辑删除
     * @throws IllegalStateException 仓储更新的影响行数不是一行
     */
    public UserProfile update(String id, SaveUserCommand command) {
        User existing = findExisting(id);
        String name = User.requireText(command.name(), "name");
        String email = User.requireText(command.email(), "email");

        // 意图与摘要一起传给仓储：未改密码时不能把已读取的旧摘要写回数据库。
        boolean passwordChanged = command.password() != null;
        String hash = passwordChanged ? hasher.encode(command.password()) : existing.passwordHash();
        User updated =
                new User(
                        existing.id(),
                        name,
                        email,
                        hash,
                        existing.status(),
                        null,
                        existing.createAt(),
                        now());
        requireOneRow(repository.update(updated, passwordChanged));

        // 返回本次用例快照，不重新读取并发变更后的 status 或 createAt。
        return UserProfile.from(updated);
    }

    /**
     * 逻辑删除一个当前可见用户，不删除物理记录或修改账户状态。
     *
     * <p>先查询未删除快照，再向仓储传递 ID 与微秒精度更新时间。 deletedAt 由数据库生成；写入必须影响一行。重复删除按未找到处理，
     * 不清理关联资源，先查后写也不提供完整的并发冲突治理。
     *
     * @param id 待删除用户 ID，查询前裁剪首尾空白
     * @throws IllegalArgumentException ID 缺失或为空白
     * @throws UserNotFoundException 用户不存在或已经逻辑删除
     * @throws IllegalStateException 仓储逻辑删除的影响行数不是一行
     */
    public void delete(String id) {
        User existing = findExisting(id);
        requireOneRow(repository.softDelete(existing.id(), now()));
    }

    /**
     * 为详情、更新和删除取得统一的未删除用户快照。
     *
     * <p>复用领域文本规则检查并裁剪 ID，在仓储查询后再次排除 deletedAt 非空的记录。 账户 status 不影响可见性；缺失和逻辑删除使用同一种未找到异常。
     *
     * @return 查询时的完整内部用户数据，包含只供内部使用的密码摘要
     * @throws IllegalArgumentException ID 缺失或为空白
     * @throws UserNotFoundException 用户不存在或已经逻辑删除
     */
    private User findExisting(String id) {
        return repository
                .findById(User.requireText(id, "id"))
                .filter(user -> user.deletedAt() == null)
                .orElseThrow(UserNotFoundException::new);
    }

    /**
     * 从注入时钟读取当前时刻，并截断到 DATETIME(6) 对应的微秒精度。
     *
     * <p>保留 Instant 的绝对时刻语义，不转换为服务器本地时区。 应用写入与返回快照使用相同精度，避免数据库重新读取后出现纳秒尾差。
     *
     * @return 微秒精度的当前时刻
     */
    private Instant now() {
        // 表精度为微秒，让写入响应和数据库重新读取的时间精度一致。
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 检查资料更新或逻辑删除是否恰好影响一行，避免将失效写入当成成功。
     *
     * <p>零行可能来自先查后写之间的记录变化，多行同样违反单用户写入合同。 统一报告内部失败，不在此推断冲突原因、重试或增加并发控制。
     *
     * @throws IllegalStateException 影响行数不是一行
     */
    private static void requireOneRow(int affectedRows) {
        if (affectedRows != 1) {
            throw new IllegalStateException("用户写入结果不符合预期");
        }
    }
}
