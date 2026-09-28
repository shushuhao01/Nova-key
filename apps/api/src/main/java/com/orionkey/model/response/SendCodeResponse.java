package com.orionkey.model.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 发送邮箱验证码响应。
 * 仅返回与「用户是否注册」无关的全局配置，避免通过响应差异枚举出已注册邮箱。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SendCodeResponse {

    /** 验证码有效期（分钟） */
    private int expireMinutes;

    /** 允许重新发送的间隔（秒） */
    private int resendAfterSeconds;
}
