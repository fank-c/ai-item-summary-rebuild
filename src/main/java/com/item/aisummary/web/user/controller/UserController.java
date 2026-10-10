package com.item.aisummary.web.user.controller;

import com.item.aisummary.application.user.command.SaveUserCommand;
import com.item.aisummary.application.user.service.UserService;
import com.item.aisummary.web.user.request.CreateUserRequest;
import com.item.aisummary.web.user.request.UpdateUserRequest;
import com.item.aisummary.web.user.response.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 用户管理 HTTP 入口；保持 /api/users 合同，本阶段不实施登录或所有权校验。
 * 请求校验通过后转换为应用命令，响应只映射公开资料，不返回密码摘要。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(201).body(UserResponse.from(service.create(
            new SaveUserCommand(request.name(), request.email(), request.password()))));
    }

    @GetMapping
    public List<UserResponse> findAll() {
        return service.findAll().stream().map(UserResponse::from).toList();
    }

    @GetMapping("/{id}")
    public UserResponse findById(@PathVariable String id) {
        return UserResponse.from(service.findById(id));
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable String id, @Valid @RequestBody UpdateUserRequest request) {
        return UserResponse.from(service.update(id, new SaveUserCommand(request.name(), request.email(), request.password())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
