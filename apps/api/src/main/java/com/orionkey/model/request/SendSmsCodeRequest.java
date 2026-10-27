package com.orionkey.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** 发送短信验证码请求（登录 / 找回密码 / 绑定手机号） */
@Getter
@Setter
public class SendSmsCodeRequest {

    @NotBlank(message = "Phone is required")
    private String phone;

    /** 场景：LOGIN（验证码登录）/ PASSWORD_RESET（找回密码）/ BIND_PHONE（绑定手机号） */
    @NotBlank(message = "Scene is required")
    private String scene;
}
