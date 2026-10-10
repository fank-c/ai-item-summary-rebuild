package com.item.aisummary.web.user.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建校验先于服务裁剪；摘要适配器另行检查密码 UTF-8 字节上限。 */
public record CreateUserRequest(
        /** 用户名称；HTTP 层校验后由应用服务裁剪。 */
        @NotBlank(message = "名称不能为空")
        @Size(max = 100, message = "名称长度不能超过 100 个字符")
        String name,

        /** 用户邮箱；HTTP 层校验格式和长度。 */
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 254, message = "邮箱长度不能超过 254 个字符")
        String email,

        /** 原始密码；只传递到密码摘要边界，不写入日志。 */
        @NotBlank(message = "密码不能为空")
        @Size(max = 255, message = "密码长度不能超过 255 个字符")
        String password
) {

    /** 避免 record 默认字符串把原始密码及用户资料带入日志。 */
    @Override
    public String toString() {
        return "CreateUserRequest[redacted]";
    }
}
