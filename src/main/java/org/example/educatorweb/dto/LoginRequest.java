package org.example.educatorweb.dto;

import jakarta.validation.constraints.NotBlank;

public class LoginRequest {
    @NotBlank(message = "手机号不能为空")
    private String phone;

    private String password;     // 密码登录时使用

    private String code;         // 验证码登录时使用

    private String loginMode;    // "password" 或 "code"

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getLoginMode() { return loginMode; }
    public void setLoginMode(String loginMode) { this.loginMode = loginMode; }
}
