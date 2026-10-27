package com.orionkey.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 手机号自助注册请求 */
@Getter
@Setter
public class RegisterPhoneRequest {

    @NotBlank(message = "Phone is required")
    private String phone;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 64, message = "Password must be 8-64 characters")
    private String password;

    @NotBlank(message = "Confirm password is required")
    private String confirmPassword;

    @NotBlank(message = "Captcha ID is required")
    private String captchaId;

    @NotBlank(message = "Captcha is required")
    private String captcha;

    /** 推广员邀请码（选填） */
    private String inviteCode;
}
