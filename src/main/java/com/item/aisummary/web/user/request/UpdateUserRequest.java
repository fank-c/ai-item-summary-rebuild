package com.item.aisummary.web.user.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 名称和邮箱仍必填；password 允许省略或 null，非空白与字节上限由摘要适配器检查。 */
public record UpdateUserRequest(
    @NotBlank(message = "名称不能为空") @Size(max = 100, message = "名称长度不能超过 100 个字符") String name,
    @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确")
    @Size(max = 254, message = "邮箱长度不能超过 254 个字符") String email,
    @Size(max = 255, message = "密码长度不能超过 255 个字符") String password
) {
    // 修改命令可能含新密码，禁止使用 record 默认字符串输出字段内容。
    @Override
    public String toString() {
        return "UpdateUserRequest[redacted]";
    }
}
