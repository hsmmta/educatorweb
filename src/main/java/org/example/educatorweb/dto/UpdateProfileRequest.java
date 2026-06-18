package org.example.educatorweb.dto;

import jakarta.validation.constraints.NotBlank;

public class UpdateProfileRequest {
    @NotBlank(message = "手机号不能为空")
    private String phone;

    private String nickname;
    private String email;

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
