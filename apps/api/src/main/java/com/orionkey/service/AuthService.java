package com.orionkey.service;

import com.orionkey.model.request.CodeLoginRequest;
import com.orionkey.model.request.LoginRequest;
import com.orionkey.model.request.RegisterRequest;
import com.orionkey.model.request.ResetPasswordRequest;
import com.orionkey.model.request.SendCodeRequest;
import com.orionkey.model.response.AuthResponse;
import com.orionkey.model.response.CaptchaResponse;
import com.orionkey.model.response.SendCodeResponse;

public interface AuthService {

    CaptchaResponse generateCaptcha();

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request, String sessionToken);

    /**
     * 发送邮箱验证码（忘记密码 / 验证码登录）。
     * 含频率限制（发送间隔 + 每日上限）与防用户枚举（邮箱未注册时静默返回成功且不发送）。
     *
     * @param request 邮箱 + 场景（PASSWORD_RESET / LOGIN）
     * @param ip      请求方 IP（仅记录，用于审计）
     */
    SendCodeResponse sendEmailCode(SendCodeRequest request, String ip);

    /** 忘记密码：校验邮箱验证码 → 重置密码（并使旧登录态失效） */
    void resetPassword(ResetPasswordRequest request);

    /** 验证码登录：邮箱 + 验证码换取登录态 */
    AuthResponse loginByCode(CodeLoginRequest request, String sessionToken);

    void logout();
}
