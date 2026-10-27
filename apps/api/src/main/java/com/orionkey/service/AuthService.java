package com.orionkey.service;

import com.orionkey.model.request.CodeLoginRequest;
import com.orionkey.model.request.ForceChangePasswordRequest;
import com.orionkey.model.request.LoginRequest;
import com.orionkey.model.request.RegisterPhoneRequest;
import com.orionkey.model.request.RegisterRequest;
import com.orionkey.model.request.ResetPasswordRequest;
import com.orionkey.model.request.SendCodeRequest;
import com.orionkey.model.request.SendSmsCodeRequest;
import com.orionkey.model.request.SmsCodeLoginRequest;
import com.orionkey.model.response.AuthResponse;
import com.orionkey.model.response.CaptchaResponse;
import com.orionkey.model.response.LoginHintResponse;
import com.orionkey.model.response.SendCodeResponse;

import java.util.UUID;

public interface AuthService {

    CaptchaResponse generateCaptcha();

    AuthResponse register(RegisterRequest request);

    /** 手机号自助注册（需同时开启客户手机号 / 手机号登录 / 手机号注册开关） */
    AuthResponse registerPhone(RegisterPhoneRequest request, String ip);

    AuthResponse login(LoginRequest request, String sessionToken);

    /** 登录页默认密码提示（受频率限制保护） */
    LoginHintResponse loginHint();

    /** 强制修改初始密码（登录后未改密时仅允许访问白名单接口），返回新登录态 */
    AuthResponse forceChangePassword(UUID userId, ForceChangePasswordRequest request);

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

    /**
     * 发送手机短信验证码（LOGIN / PASSWORD_RESET / BIND_PHONE）。
     * 含短信未启用校验、频控与防用户枚举。
     *
     * @param ip 请求方 IP（仅记录，用于审计）
     */
    SendCodeResponse sendSmsCode(SendSmsCodeRequest request, String ip);

    /** 短信验证码登录：手机号 + 验证码换取登录态（管理员不支持） */
    AuthResponse loginBySms(SmsCodeLoginRequest request, String sessionToken);

    void logout();
}
