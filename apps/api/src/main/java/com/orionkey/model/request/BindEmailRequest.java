package com.orionkey.model.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BindEmailRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 当前登录密码（二次确认，防会话被盗用） */
    @NotBlank(message = "登录密码不能为空")
    private String password;
}
