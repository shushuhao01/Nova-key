package com.orionkey.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** 短信验证码登录：手机号 + 6 位验证码 */
@Getter
@Setter
public class SmsCodeLoginRequest {

    @NotBlank(message = "Phone is required")
    private String phone;

    @NotBlank(message = "Code is required")
    private String code;
}
