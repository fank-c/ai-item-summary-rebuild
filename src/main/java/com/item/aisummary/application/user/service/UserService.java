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

    /** 创建资料；普通文本先规范化，密码原样交给摘要适配器。 */
    public UserProfile create(SaveUserCommand command) {
        String name = User.requireText(command.name(), "name");
        String email = User.requireText(command.email(), "email");
        String hash = hasher.encode(command.password());
        Instant now = now();
        User saved = repository.insert(new User(null, name, email, hash, 1, null, now, now));
        return UserProfile.from(saved);
    }

    public UserProfile findById(String id) {
        return UserProfile.from(findExisting(id));
    }

    public List<UserProfile> findAll() {
        // 保留防御检查，防止绕过常规逻辑删除查询的适配器泄露已删除资料。
        return repository.findAll().stream().filter(user -> user.deletedAt() == null).map(UserProfile::from).toList();
    }

    /** 修改资料；null 密码代表保留，明确提交的密码才触发重新编码。 */
    public UserProfile update(String id, SaveUserCommand command) {
        User existing = findExisting(id);
        String name = User.requireText(command.name(), "name");
        String email = User.requireText(command.email(), "email");
        // 意图与摘要一起传给仓储：未改密码时不能把已读取的旧摘要写回数据库。
        boolean passwordChanged = command.password() != null;
        String hash = passwordChanged ? hasher.encode(command.password()) : existing.passwordHash();
        User updated = new User(existing.id(), name, email, hash, existing.status(), null, existing.createAt(), now());
        requireOneRow(repository.update(updated, passwordChanged));
        // 返回本次用例快照，不重新读取并发变更后的 status 或 createAt。
        return UserProfile.from(updated);
    }

    /** 逻辑删除资料，保留账户状态；先查询再写入不提供完整并发冲突治理。 */
    public void delete(String id) {
        User existing = findExisting(id);
        requireOneRow(repository.softDelete(existing.id(), now()));
    }

    private User findExisting(String id) {
        return repository.findById(User.requireText(id, "id"))
            .filter(user -> user.deletedAt() == null).orElseThrow(UserNotFoundException::new);
    }

    private Instant now() {
        // 表精度为微秒，让写入响应和数据库重新读取的时间精度一致。
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static void requireOneRow(int affectedRows) {
        if (affectedRows != 1) {
            throw new IllegalStateException("用户写入结果不符合预期");
        }
    }
}
