package com.orionkey.controller;

import com.orionkey.common.ApiResponse;
import com.orionkey.model.request.CodeLoginRequest;
import com.orionkey.model.request.LoginRequest;
import com.orionkey.model.request.RegisterRequest;
import com.orionkey.model.request.ResetPasswordRequest;
import com.orionkey.model.request.SendCodeRequest;
import com.orionkey.model.response.AuthResponse;
import com.orionkey.model.response.CaptchaResponse;
import com.orionkey.model.response.SendCodeResponse;
import com.orionkey.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @GetMapping("/captcha")
    public ApiResponse<CaptchaResponse> getCaptcha() {
        return ApiResponse.success(authService.generateCaptcha());
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                           @RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
        return ApiResponse.success(authService.login(request, sessionToken));
    }

    /** 发送邮箱验证码（忘记密码 / 验证码登录） */
    @PostMapping("/email-code/send")
    public ApiResponse<SendCodeResponse> sendEmailCode(@Valid @RequestBody SendCodeRequest request,
                                                       HttpServletRequest httpRequest) {
        return ApiResponse.success(authService.sendEmailCode(request, getClientIp(httpRequest)));
    }

    /** 忘记密码：邮箱验证码校验通过后重置密码 */
    @PostMapping("/forgot-password/reset")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.success();
    }

    /** 验证码登录 */
    @PostMapping("/login-by-code")
    public ApiResponse<AuthResponse> loginByCode(@Valid @RequestBody CodeLoginRequest request,
                                                 @RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
        return ApiResponse.success(authService.loginByCode(request, sessionToken));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.success();
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip != null && ip.contains(",") ? ip.split(",")[0].trim() : ip;
    }
}
