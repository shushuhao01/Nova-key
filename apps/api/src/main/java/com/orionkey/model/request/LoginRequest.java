package com.orionkey.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {

    @NotBlank(message = "Account is required")
    private String account;

    @NotBlank(message = "Password is required")
    private String password;

    /**
     * 延迟强制改密：仅支付成功页自动登录使用。
     * 为 true 时签发的 token 带 deferPwd 标记，本次会话可暂不强制改密；
     * 数据库标记保持不变，下次正常登录仍会强制改密。
     */
    private boolean deferForceChange;
}
