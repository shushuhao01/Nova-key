package com.orionkey.model.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 登录页提示（默认密码由后台 phone_default_password 实时下发） */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LoginHintResponse {
    private String defaultPassword;
}
