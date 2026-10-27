package com.orionkey.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 忘记密码：校验邮箱验证码后重置密码 */
@Getter
@Setter
public class ResetPasswordRequest {

    /** 邮箱；也允许填手机号（该账号需已绑定邮箱） */
    @NotBlank(message = "Email is required")
    private String email;

    @NotBlank(message = "Code is required")
    private String code;

    @NotBlank(message = "Password is required")
    @Size(min = 6, max = 64, message = "Password must be 6-64 characters")
    private String newPassword;
}
