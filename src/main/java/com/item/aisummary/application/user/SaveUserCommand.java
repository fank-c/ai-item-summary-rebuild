package com.item.aisummary.application.user;

/** 应用用例输入；不依赖 HTTP DTO，密码原样传入编码边界。 */
public record SaveUserCommand(String name, String email, String password) {
    @Override
    public String toString() {
        return "SaveUserCommand[redacted]";
    }
}
