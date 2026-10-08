package com.item.aisummary.web.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
    @NotBlank(message = "名称不能为空") @Size(max = 100, message = "名称长度不能超过 100 个字符") String name,
    @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确")
    @Size(max = 254, message = "邮箱长度不能超过 254 个字符") String email,
    @NotBlank(message = "密码不能为空") @Size(max = 255, message = "密码长度不能超过 255 个字符") String password
) {
    @Override
    public String toString() {
        return "CreateUserRequest[redacted]";
    }
}
