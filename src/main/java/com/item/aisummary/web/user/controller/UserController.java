package com.item.aisummary.web.user.controller;

import com.item.aisummary.application.user.command.SaveUserCommand;
import com.item.aisummary.application.user.service.UserService;
import com.item.aisummary.web.user.request.CreateUserRequest;
import com.item.aisummary.web.user.request.UpdateUserRequest;
import com.item.aisummary.web.user.response.UserResponse;
import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户管理 HTTP 入口；保持 /api/users 合同，本阶段不实施登录或所有权校验。 请求校验通过后转换为应用命令，响应只映射公开资料，不返回密码摘要。 */
@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    /**
     * 创建新用户并返回其公开资料。
     *
     * <p>HTTP 层完成参数校验后组装应用层命令；密码由服务层使用 BCrypt 单向摘要。
     * 响应使用 HTTP 201 Created，公开六字段资料，不包含密码摘要或软删除时间。
     *
     * @param request 包含用户名、邮箱和明文密码的创建请求
     * @return 包含 201 状态码和新建用户公开资料的响应实体
     */
    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        SaveUserCommand command =
                new SaveUserCommand(request.name(), request.email(), request.password());
        return ResponseEntity.status(201).body(UserResponse.from(service.create(command)));
    }

    /**
     * 查询系统中全部未逻辑删除的用户。
     *
     * <p>按仓储返回顺序映射公开资料，过滤已逻辑删除的记录。不分页、不按状态过滤。
     *
     * @return 未删除用户的公开资料列表；若无记录返回空列表
     */
    @GetMapping
    public List<UserResponse> findAll() {
        return service.findAll().stream().map(UserResponse::from).toList();
    }

    /**
     * 根据用户 ID 查询单个未删除用户资料。
     *
     * @param id 用户唯一标识，路径参数
     * @return 对应用户的公开资料
     */
    @GetMapping("/{id}")
    public UserResponse findById(@PathVariable String id) {
        return UserResponse.from(service.findById(id));
    }

    /**
     * 更新指定未删除用户的资料。
     *
     * <p>名称和邮箱必填；密码为 null 时表示保留原密码摘要，非 null 时重新计算摘要。
     *
     * @param id 目标用户的唯一标识，路径参数
     * @param request 包含新名称、新邮箱及可选新密码的修改请求
     * @return 更新后用户的公开资料
     */
    @PutMapping("/{id}")
    public UserResponse update(
            @PathVariable String id, @Valid @RequestBody UpdateUserRequest request) {
        SaveUserCommand command =
                new SaveUserCommand(request.name(), request.email(), request.password());
        return UserResponse.from(service.update(id, command));
    }

    /**
     * 逻辑删除指定用户。
     *
     * <p>将该用户的 deleted_at 置为当前时间戳，保留账户状态与物理记录。
     * 成功执行后返回 HTTP 204 No Content。
     *
     * @param id 待删除用户的唯一标识，路径参数
     * @return 204 空响应实体
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
