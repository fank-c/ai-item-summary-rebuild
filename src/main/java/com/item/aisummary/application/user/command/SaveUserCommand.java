package com.item.aisummary.application.user.command;

/** 应用用例输入；不依赖 HTTP DTO，密码原样传入编码边界。 创建时密码必填；更新时 null 代表保留已有摘要，空字符串不代表保留。 */
public record SaveUserCommand(
        /** 用户展示名称；服务层会裁剪普通文本。 */
        String name,
        /** 用户邮箱；服务层会裁剪普通文本。 */
        String email,
        /** 原始密码；只在摘要适配器边界编码。 */
        String password) {
    @Override
    public String toString() {
        return "SaveUserCommand[redacted]";
    }
}
