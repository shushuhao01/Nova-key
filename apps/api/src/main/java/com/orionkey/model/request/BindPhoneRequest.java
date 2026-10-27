package com.orionkey.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BindPhoneRequest {

    @NotBlank(message = "手机号不能为空")
    private String phone;

    /** 当前登录密码（二次确认，防会话被盗用） */
    @NotBlank(message = "登录密码不能为空")
    private String password;
}
