package com.item.aisummary.web.user.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 名称和邮箱仍必填；password 为 null 时保留旧摘要。 */
public record UpdateUserRequest(
        /** 新的用户名称；HTTP 层校验后由应用服务裁剪。 */
        @NotBlank(message = "名称不能为空")
        @Size(max = 100, message = "名称长度不能超过 100 个字符")
        String name,

        /** 新的用户邮箱；HTTP 层校验格式和长度。 */
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 254, message = "邮箱长度不能超过 254 个字符")
        String email,

        /** 可选的新密码；null 表示保留原摘要，空白值会被拒绝。 */
        @Size(max = 255, message = "密码长度不能超过 255 个字符")
        String password
) {

    /** 修改命令可能含新密码，禁止使用 record 默认字符串输出字段内容。 */
    @Override
    public String toString() {
        return "UpdateUserRequest[redacted]";
    }
}
