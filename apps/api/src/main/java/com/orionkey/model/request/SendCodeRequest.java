package com.orionkey.model.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** 发送邮箱验证码请求（忘记密码 / 验证码登录） */
@Getter
@Setter
public class SendCodeRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    /** 场景：PASSWORD_RESET（忘记密码）/ LOGIN（验证码登录） */
    @NotBlank(message = "Scene is required")
    private String scene;
}
