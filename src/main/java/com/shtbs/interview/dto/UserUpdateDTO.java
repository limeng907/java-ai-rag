package com.shtbs.interview.dto;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public class UserUpdateDTO {

    @Size(min = 3, max = 20, message = "用户名长度必须在3~20之间")
    private String username;

    @Size(min = 6, max = 20, message = "密码长度必须在6~20之间")
    private String password;

    @Email(message = "邮箱格式不正确")
    private String email;


    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
