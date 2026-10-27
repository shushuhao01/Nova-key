package com.orionkey.service.impl;

import com.orionkey.constant.ErrorCode;
import com.orionkey.constant.UserRole;
import com.orionkey.entity.CartItem;
import com.orionkey.entity.EmailVerificationCode;
import com.orionkey.entity.SiteConfig;
import com.orionkey.entity.User;
import com.orionkey.exception.BusinessException;
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
import com.orionkey.model.response.UserProfileResponse;
import com.orionkey.repository.CartItemRepository;
import com.orionkey.repository.EmailVerificationCodeRepository;
import com.orionkey.repository.SiteConfigRepository;
import com.orionkey.repository.UserRepository;
import com.orionkey.service.AuthService;
import com.orionkey.service.DistributionService;
import com.orionkey.service.NotificationService;
import com.orionkey.utils.CaptchaUtils;
import com.orionkey.utils.JwtUtils;
import com.orionkey.util.PhoneUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final CartItemRepository cartItemRepository;
    private final EmailVerificationCodeRepository emailVerificationCodeRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final CaptchaUtils captchaUtils;
    private final NotificationService notificationService;
    private final PermissionResolver permissionResolver;
    private final DistributionService distributionService;
    private final SmsCodeService smsCodeService;

    /** 手机号注册的默认用户名前缀：p<随机数字><手机尾号> */
    private static final String PHONE_USERNAME_PREFIX = "p";
    /** 单 IP 注册计数（按自然日），配合 site_configs.phone_register_daily_limit 限流 */
    private static final Map<String, IpRegisterCounter> REGISTER_IP_COUNTERS = new ConcurrentHashMap<>();

    @Override
    public CaptchaResponse generateCaptcha() {
        CaptchaUtils.CaptchaResult result = captchaUtils.generate();
        return new CaptchaResponse(result.captchaId(), result.imageBase64());
    }

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (!captchaUtils.verify(request.getCaptchaId(), request.getCaptcha())) {
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID, "验证码错误或已过期");
        }
        // 邮箱统一小写，避免大小写不同导致的重复注册（USER@qq.com == user@QQ.com）
        String email = request.getEmail() == null ? "" : request.getEmail().trim().toLowerCase();
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS, "用户名已存在");
        }
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_EXISTS, "该邮箱已注册");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(UserRole.USER);
        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // 并发/重复提交穿透 existsBy 检查时，DB 唯一约束冲突 → 明确提示，避免落到 500"系统异常"
            log.warn("Register unique constraint conflict: username={}, email={}", request.getUsername(), email);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名或邮箱已被注册，请更换后重试");
        }

        // 管理员通知：新用户注册
        try {
            notificationService.sendTemplate("REGISTER", Map.of(
                    "username", user.getUsername(),
                    "email", user.getEmail()));
        } catch (Exception e) {
            log.warn("Register notification failed: {}", e.getMessage());
        }

        // 邀请码绑定推广员（选填）：无效/异常不阻塞注册
        if (request.getInviteCode() != null && !request.getInviteCode().isBlank()) {
            try {
                distributionService.bindCustomerByInviteCode(user.getId(), request.getInviteCode());
                log.info("User {} registered and bound by invite code {}", user.getId(), request.getInviteCode().trim());
            } catch (Exception e) {
                log.warn("Register invite bind skipped: {}", e.getMessage());
            }
        }

        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole().name(), user.getPasswordVersion());
        return new AuthResponse(token, UserProfileResponse.from(user, permissionResolver.resolve(user)));
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse registerPhone(RegisterPhoneRequest request, String ip) {
        // 前置开关：客户手机号 / 手机号登录 / 手机号注册同时开启才允许自助注册
        if (!isConfigEnabled("customer_phone_enabled")
                || !isConfigEnabled("phone_login_enabled")
                || !isConfigEnabled("phone_register_enabled")) {
            throw new BusinessException(ErrorCode.PHONE_FEATURE_DISABLED, "手机号注册未启用");
        }

        String phone = PhoneUtils.normalize(request.getPhone());
        if (!PhoneUtils.isValidCnMobile(phone)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号格式不正确");
        }
        if (userRepository.existsByPhone(phone)) {
            throw new BusinessException(ErrorCode.PHONE_EXISTS, "该手机号已注册，请直接登录");
        }
        if (request.getPassword() == null || !request.getPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException(ErrorCode.PASSWORD_MISMATCH, "两次输入的密码不一致");
        }
        validatePasswordStrength(request.getPassword());

        if (!captchaUtils.verify(request.getCaptchaId(), request.getCaptcha())) {
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID, "验证码错误或已过期");
        }

        // 单 IP 日注册上限（默认 20），仅在真正落库成功后计数
        String clientIp = ip == null ? "" : ip.trim();
        int dailyLimit = intConfig("phone_register_daily_limit", 20);
        IpRegisterCounter counter = acquireRegisterCounter(clientIp, dailyLimit);

        User user = new User();
        user.setUsername(resolvePhoneUsername(phone));
        user.setPhone(phone);
        user.setPhoneRegisteredAt(LocalDateTime.now());
        user.setEmail(null);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(UserRole.USER);
        // 用户自设密码，无需强制修改
        user.setMustChangePassword(false);
        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            log.warn("Phone register unique conflict: phone={}", phone);
            throw new BusinessException(ErrorCode.PHONE_EXISTS, "该手机号已注册，请直接登录");
        }
        counter.increment();

        // 管理员通知：新用户注册（手机号注册无邮箱，避免 Map.of 传 null 触发 NPE）
        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("username", user.getUsername());
            vars.put("phone", phone);
            notificationService.sendTemplate("REGISTER", vars);
        } catch (Exception e) {
            log.warn("Register notification failed: {}", e.getMessage());
        }

        if (request.getInviteCode() != null && !request.getInviteCode().isBlank()) {
            try {
                distributionService.bindCustomerByInviteCode(user.getId(), request.getInviteCode());
                log.info("Phone user {} registered and bound by invite code {}", user.getId(), request.getInviteCode().trim());
            } catch (Exception e) {
                log.warn("Phone register invite bind skipped: {}", e.getMessage());
            }
        }

        log.info("Phone register success: userId={}, phone={}", user.getId(), phone);
        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole().name(), user.getPasswordVersion());
        return new AuthResponse(token, UserProfileResponse.from(user, permissionResolver.resolve(user)));
    }

    /** 连续登录失败上限 */
    private static final int MAX_FAILED_ATTEMPTS = 5;
    /** 账户锁定时长（分钟） */
    private static final int LOCK_DURATION_MINUTES = 15;

    // ── 邮箱验证码 ──
    /** 验证码场景：忘记密码 */
    private static final String SCENE_PASSWORD_RESET = "PASSWORD_RESET";
    /** 验证码场景：验证码登录 */
    private static final String SCENE_LOGIN = "LOGIN";
    /** 验证码模板编码（消息通知模板，可在后台自定义文案） */
    private static final String TPL_PASSWORD_RESET = "PASSWORD_RESET_CODE";
    private static final String TPL_LOGIN = "LOGIN_CODE";
    /** 单个验证码最大校验次数，超过则作废需重新获取 */
    private static final int MAX_CODE_ATTEMPTS = 5;
    /** 验证码有效期默认值（分钟），后台 site_configs.auth_code_expire_minutes 覆盖 */
    private static final int DEFAULT_CODE_EXPIRE_MINUTES = 10;
    /** 验证码重发间隔默认值（秒），后台 site_configs.auth_code_send_interval_seconds 覆盖 */
    private static final int DEFAULT_CODE_SEND_INTERVAL_SECONDS = 60;
    /** 每邮箱每日发送上限默认值，后台 site_configs.auth_code_daily_limit 覆盖 */
    private static final int DEFAULT_CODE_DAILY_LIMIT = 10;

    private static final SecureRandom CODE_RANDOM = new SecureRandom();

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse login(LoginRequest request, String sessionToken) {
        // 统一登录入口自动识别：含 @ → 邮箱（小写）；手机号正则 → 手机号；其余 → 用户名
        String account = request.getAccount() == null ? "" : request.getAccount().trim();
        User user;
        if (account.contains("@")) {
            String email = account.toLowerCase(Locale.ROOT);
            user = userRepository.findByUsernameOrEmail(email, email)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS, "用户名或密码错误"));
        } else {
            String phone = PhoneUtils.normalize(account);
            if (PhoneUtils.isValidCnMobile(phone)) {
                if (!isConfigEnabled("phone_login_enabled")) {
                    throw new BusinessException(ErrorCode.PHONE_LOGIN_DISABLED, "手机号登录未启用");
                }
                user = userRepository.findByPhone(phone)
                        .orElseThrow(() -> new BusinessException(ErrorCode.PHONE_NOT_REGISTERED, "该手机号未注册，请先注册"));
                if (user.getRole() == UserRole.ADMIN) {
                    throw new BusinessException(ErrorCode.ADMIN_PHONE_LOGIN_FORBIDDEN, "管理员请使用账号或邮箱登录");
                }
            } else {
                user = userRepository.findByUsernameOrEmail(account, account)
                        .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS, "用户名或密码错误"));
            }
        }

        if (user.getIsDeleted() == 1) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "该账号已被禁用");
        }

        // 账户锁定检查
        if (user.getLockUntil() != null) {
            if (user.getLockUntil().isAfter(java.time.LocalDateTime.now())) {
                long remainMinutes = java.time.Duration.between(java.time.LocalDateTime.now(), user.getLockUntil()).toMinutes() + 1;
                throw new BusinessException(ErrorCode.ACCOUNT_LOCKED,
                        "账号已被锁定，请 " + remainMinutes + " 分钟后再试");
            }
            // 锁定已过期：重置失败计数，给予完整的重试机会
            user.setFailedLoginAttempts(0);
            user.setLockUntil(null);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            // 记录失败次数
            user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
            if (user.getFailedLoginAttempts() >= MAX_FAILED_ATTEMPTS) {
                user.setLockUntil(java.time.LocalDateTime.now().plusMinutes(LOCK_DURATION_MINUTES));
                userRepository.save(user);
                log.warn("Account locked due to {} failed login attempts: {}", MAX_FAILED_ATTEMPTS, user.getUsername());
                throw new BusinessException(ErrorCode.ACCOUNT_LOCKED,
                        "连续登录失败 " + MAX_FAILED_ATTEMPTS + " 次，账号已锁定 " + LOCK_DURATION_MINUTES + " 分钟");
            }
            userRepository.save(user);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "用户名或密码错误");
        }

        // 登录成功：重置失败计数和锁定状态
        if (user.getFailedLoginAttempts() > 0 || user.getLockUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockUntil(null);
            userRepository.save(user);
        }

        // Merge guest cart on login
        if (StringUtils.hasText(sessionToken)) {
            mergeCart(sessionToken, user.getId());
        }

        // 支付成功页自动登录可选择延迟强制改密：token 带 deferPwd，本次会话放行；数据库标记保持不变
        boolean defer = request.isDeferForceChange() && user.isMustChangePassword();
        if (defer) {
            log.info("Login with deferred force-change: userId={}", user.getId());
        }
        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole().name(),
                user.getPasswordVersion(), defer);
        return new AuthResponse(token, UserProfileResponse.from(user, permissionResolver.resolve(user)));
    }

    @Override
    public LoginHintResponse loginHint() {
        return new LoginHintResponse(strConfig("phone_default_password", "Aa123456"));
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse forceChangePassword(UUID userId, ForceChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "登录已失效，请重新登录"));
        if (user.getIsDeleted() == 1) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "该账号已被禁用");
        }
        if (request.getOldPassword() == null
                || !passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.OLD_PASSWORD_WRONG, "原密码不正确");
        }
        if (request.getNewPassword() == null || !request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException(ErrorCode.PASSWORD_MISMATCH, "两次输入的密码不一致");
        }
        if (request.getNewPassword().equals(request.getOldPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "新密码不能与原密码相同");
        }
        validatePasswordStrength(request.getNewPassword());

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        // passwordVersion +1 使旧 token 立即失效，返回的新 token 供前端续用
        user.setPasswordVersion(user.getPasswordVersion() + 1);
        user.setMustChangePassword(false);
        user.setFailedLoginAttempts(0);
        user.setLockUntil(null);
        userRepository.save(user);

        log.info("Force change password success: userId={}", user.getId());
        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole().name(), user.getPasswordVersion());
        return new AuthResponse(token, UserProfileResponse.from(user, permissionResolver.resolve(user)));
    }

    // ═══════════ 邮箱验证码：发送 / 忘记密码 / 验证码登录 ═══════════

    @Override
    @Transactional
    public SendCodeResponse sendEmailCode(SendCodeRequest request, String ip) {
        String scene = request.getScene() == null ? "" : request.getScene().trim().toUpperCase(Locale.ROOT);
        if (!SCENE_PASSWORD_RESET.equals(scene) && !SCENE_LOGIN.equals(scene)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的验证码场景");
        }

        int expireMinutes = intConfig("auth_code_expire_minutes", DEFAULT_CODE_EXPIRE_MINUTES);
        int intervalSeconds = intConfig("auth_code_send_interval_seconds", DEFAULT_CODE_SEND_INTERVAL_SECONDS);
        int dailyLimit = intConfig("auth_code_daily_limit", DEFAULT_CODE_DAILY_LIMIT);

        // 找回密码支持「邮箱」或「手机号」输入：手机号账号走其绑定邮箱；未注册手机号静默成功（防枚举）
        String email;
        if (SCENE_PASSWORD_RESET.equals(scene)) {
            email = resolvePasswordResetEmail(request.getEmail(), true);
            if (email == null) {
                log.info("Skip sending reset code (phone not registered or disabled)");
                return new SendCodeResponse(expireMinutes, intervalSeconds);
            }
        } else {
            email = normalizeEmail(request.getEmail());
        }
        LocalDateTime now = LocalDateTime.now();

        // 重发间隔限制（以最近一次发送时间为准，无论是否已使用）
        EmailVerificationCode last = emailVerificationCodeRepository
                .findTopByEmailAndSceneOrderByCreatedAtDesc(email, scene).orElse(null);
        if (last != null && last.getCreatedAt() != null
                && last.getCreatedAt().plusSeconds(intervalSeconds).isAfter(now)) {
            long wait = java.time.Duration.between(now, last.getCreatedAt().plusSeconds(intervalSeconds)).getSeconds() + 1;
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "请求过于频繁，请 " + wait + " 秒后再试");
        }

        // 每邮箱每日发送上限
        long sentToday = emailVerificationCodeRepository.countByEmailAndSceneAndCreatedAtGreaterThanEqual(
                email, scene, now.toLocalDate().atStartOfDay());
        if (sentToday >= dailyLimit) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "今日验证码发送次数已达上限，请明天再试");
        }

        // 防用户枚举：邮箱未注册或已禁用时静默返回成功（不发送、不落库）
        User user = userRepository.findByUsernameOrEmail(email, email).orElse(null);
        if (user == null || user.getIsDeleted() == 1) {
            log.info("Skip sending email code (user not found or disabled): email={}, scene={}", email, scene);
            return new SendCodeResponse(expireMinutes, intervalSeconds);
        }

        // 同场景旧未用码作废，并清理历史过期码
        emailVerificationCodeRepository.findTopByEmailAndSceneAndUsedFalseOrderByCreatedAtDesc(email, scene)
                .ifPresent(old -> {
                    old.setUsed(true);
                    emailVerificationCodeRepository.save(old);
                });
        emailVerificationCodeRepository.deleteByEmailAndSceneAndExpiresAtBefore(email, scene, now.minusDays(1));

        String code = generateNumericCode();
        EmailVerificationCode record = new EmailVerificationCode();
        record.setEmail(email);
        record.setScene(scene);
        record.setCode(code);
        record.setExpiresAt(now.plusMinutes(expireMinutes));
        record.setUsed(false);
        record.setAttemptCount(0);
        record.setIp(ip);
        emailVerificationCodeRepository.save(record);

        try {
            String templateCode = SCENE_PASSWORD_RESET.equals(scene) ? TPL_PASSWORD_RESET : TPL_LOGIN;
            notificationService.sendTemplateToEmail(templateCode, email,
                    Map.of("code", code, "expire_minutes", expireMinutes));
        } catch (Exception e) {
            log.error("Send email code failed: email={}, scene={}, err={}", email, scene, e.getMessage());
            // 抛异常触发事务回滚，避免留下「已发码但邮件未送达」的脏记录
            throw new BusinessException(ErrorCode.SERVER_ERROR, "验证码发送失败，请稍后重试");
        }

        log.info("Email code sent: email={}, scene={}, expireMinutes={}", email, scene, expireMinutes);
        return new SendCodeResponse(expireMinutes, intervalSeconds);
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public void resetPassword(ResetPasswordRequest request) {
        // 输入可为邮箱，也可为手机号（转发到该账号绑定邮箱的验证码）
        String email = resolvePasswordResetEmail(request.getEmail(), false);
        User user = userRepository.findByUsernameOrEmail(email, email)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_CODE_INVALID, "邮箱或验证码错误"));
        if (user.getIsDeleted() == 1) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "该账号已被禁用");
        }

        verifyAndConsumeCode(email, SCENE_PASSWORD_RESET, request.getCode());

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        // passwordVersion +1 让已签发的旧 JWT 立即失效（其他设备需重新登录）
        user.setPasswordVersion(user.getPasswordVersion() + 1);
        // 重置失败计数与锁定，避免刚改完密码仍被锁定
        user.setFailedLoginAttempts(0);
        user.setLockUntil(null);
        userRepository.save(user);
        log.info("Password reset via email code: userId={}", user.getId());
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse loginByCode(CodeLoginRequest request, String sessionToken) {
        String email = normalizeEmail(request.getEmail());
        User user = userRepository.findByUsernameOrEmail(email, email)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_CODE_INVALID, "邮箱或验证码错误"));
        if (user.getIsDeleted() == 1) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "该账号已被禁用");
        }

        verifyAndConsumeCode(email, SCENE_LOGIN, request.getCode());

        // 登录成功：重置失败计数和锁定状态
        if (user.getFailedLoginAttempts() > 0 || user.getLockUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockUntil(null);
            userRepository.save(user);
        }

        if (StringUtils.hasText(sessionToken)) {
            mergeCart(sessionToken, user.getId());
        }

        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole().name(), user.getPasswordVersion());
        return new AuthResponse(token, UserProfileResponse.from(user, permissionResolver.resolve(user)));
    }

    // ═══════════ 手机短信验证码：发送 / 验证码登录 ═══════════

    @Override
    public SendCodeResponse sendSmsCode(SendSmsCodeRequest request, String ip) {
        return smsCodeService.sendCode(request.getPhone(), request.getScene(), ip);
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse loginBySms(SmsCodeLoginRequest request, String sessionToken) {
        if (!isConfigEnabled("sms_enabled")) {
            throw new BusinessException(ErrorCode.SMS_NOT_ENABLED, "短信服务未启用");
        }
        if (!isConfigEnabled("phone_code_login_enabled")) {
            throw new BusinessException(ErrorCode.PHONE_FEATURE_DISABLED, "手机号验证码登录未启用");
        }
        String phone = PhoneUtils.normalize(request.getPhone());
        if (!PhoneUtils.isValidCnMobile(phone)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号格式不正确");
        }

        User user = userRepository.findByPhone(phone)
                .orElseThrow(() -> new BusinessException(ErrorCode.CAPTCHA_INVALID, "手机号或验证码错误"));
        if (user.getIsDeleted() == 1) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "该账号已被禁用");
        }
        if (user.getRole() == UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.ADMIN_PHONE_LOGIN_FORBIDDEN, "管理员请使用账号或邮箱登录");
        }

        smsCodeService.verifyAndConsume(phone, SmsCodeService.SCENE_LOGIN, request.getCode());

        // 登录成功：重置失败计数和锁定状态
        if (user.getFailedLoginAttempts() > 0 || user.getLockUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockUntil(null);
            userRepository.save(user);
        }

        if (StringUtils.hasText(sessionToken)) {
            mergeCart(sessionToken, user.getId());
        }

        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole().name(), user.getPasswordVersion());
        return new AuthResponse(token, UserProfileResponse.from(user, permissionResolver.resolve(user)));
    }

    /**
     * 校验并一次性消费验证码：
     * 不存在/已过期 → 直接失败；校验失败累加尝试次数，达到上限则作废；
     * 校验成功标记已用，防止重放。调用方需以 {@code noRollbackFor = BusinessException.class}
     * 保证失败时的尝试次数/作废状态能落库。
     */
    private void verifyAndConsumeCode(String email, String scene, String inputCode) {
        EmailVerificationCode record = emailVerificationCodeRepository
                .findTopByEmailAndSceneAndUsedFalseOrderByCreatedAtDesc(email, scene)
                .orElseThrow(() -> new BusinessException(ErrorCode.CAPTCHA_INVALID, "验证码错误或已过期"));
        if (record.getExpiresAt() == null || record.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID, "验证码错误或已过期");
        }
        if (record.getAttemptCount() >= MAX_CODE_ATTEMPTS) {
            record.setUsed(true);
            emailVerificationCodeRepository.save(record);
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID, "验证码错误次数过多，请重新获取");
        }
        if (inputCode == null || !inputCode.trim().equals(record.getCode())) {
            record.setAttemptCount(record.getAttemptCount() + 1);
            if (record.getAttemptCount() >= MAX_CODE_ATTEMPTS) {
                record.setUsed(true);
            }
            emailVerificationCodeRepository.save(record);
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID, "验证码错误或已过期");
        }
        record.setUsed(true);
        emailVerificationCodeRepository.save(record);
    }

    /** 生成 6 位数字验证码（SecureRandom，首位可为 0） */
    private String generateNumericCode() {
        return String.format("%06d", CODE_RANDOM.nextInt(1_000_000));
    }

    /** 邮箱统一小写，与注册时保持一致 */
    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** 读取整数型站点配置，缺失或非法时回退默认值 */
    private int intConfig(String key, int fallback) {
        return siteConfigRepository.findByConfigKey(key)
                .map(SiteConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .map(v -> {
                    try {
                        int parsed = Integer.parseInt(v.trim());
                        return parsed > 0 ? parsed : fallback;
                    } catch (NumberFormatException e) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    /** 读取字符串型站点配置，缺失时回退默认值 */
    private String strConfig(String key, String fallback) {
        return siteConfigRepository.findByConfigKey(key)
                .map(SiteConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(fallback);
    }

    /** 布尔开关：配置值为 true（忽略大小写）视为开启 */
    private boolean isConfigEnabled(String key) {
        return "true".equalsIgnoreCase(strConfig(key, "false").trim());
    }

    /** 密码强度：至少 8 位且同时包含字母与数字，且不得等于后台配置的默认密码 */
    private void validatePasswordStrength(String password) {
        String pwd = password == null ? "" : password;
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char c : pwd.toCharArray()) {
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (pwd.length() < 8 || !hasLetter || !hasDigit) {
            throw new BusinessException(ErrorCode.WEAK_PASSWORD, "密码至少 8 位，且需同时包含字母和数字");
        }
        if (pwd.equalsIgnoreCase(strConfig("phone_default_password", "Aa123456"))) {
            throw new BusinessException(ErrorCode.WEAK_PASSWORD, "新密码不能与默认密码相同，请设置更安全的密码");
        }
    }

    /** 手机号注册用户名：p + 4 位随机数字 + 手机尾号 4 位（纯字母+数字），避免暴露完整手机号；冲突时重新随机 */
    private String resolvePhoneUsername(String phone) {
        String tail = phone != null && phone.length() >= 4 ? phone.substring(phone.length() - 4) : "";
        for (int i = 0; i < 5; i++) {
            String candidate = PHONE_USERNAME_PREFIX + String.format("%04d", CODE_RANDOM.nextInt(10_000)) + tail;
            if (!userRepository.existsByUsername(candidate)) {
                return candidate;
            }
        }
        // 极端兜底：仍冲突则用随机 6 位数字拼接，避免注册失败
        return PHONE_USERNAME_PREFIX + String.format("%06d", CODE_RANDOM.nextInt(1_000_000)) + tail;
    }

    /** 获取（并按自然日重置）当前 IP 的注册计数器，超出日上限直接拒绝 */
    private IpRegisterCounter acquireRegisterCounter(String ip, int limit) {
        LocalDate today = LocalDate.now();
        if (REGISTER_IP_COUNTERS.size() > 5000) {
            REGISTER_IP_COUNTERS.entrySet().removeIf(e -> !today.equals(e.getValue().getDay()));
        }
        IpRegisterCounter counter = REGISTER_IP_COUNTERS.computeIfAbsent(ip, k -> new IpRegisterCounter(today));
        if (!today.equals(counter.getDay())) {
            counter.reset(today);
        }
        if (counter.getCount().get() >= limit) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "今日注册次数已达上限，请明天再试");
        }
        return counter;
    }

    /**
     * 找回密码输入解析：邮箱原样小写返回；手机号则定位账号并取其绑定邮箱。
     *
     * @param silentOnMissing 手机号未注册/已禁用时是否静默返回 null（发送验证码场景防枚举）
     */
    private String resolvePasswordResetEmail(String input, boolean silentOnMissing) {
        String raw = input == null ? "" : input.trim();
        if (raw.contains("@")) {
            return normalizeEmail(raw);
        }
        String phone = PhoneUtils.normalize(raw);
        if (!PhoneUtils.isValidCnMobile(phone)) {
            return normalizeEmail(raw);
        }
        User user = userRepository.findByPhone(phone).orElse(null);
        if (user == null || user.getIsDeleted() == 1) {
            if (silentOnMissing) {
                return null;
            }
            throw new BusinessException(ErrorCode.EMAIL_CODE_INVALID, "邮箱或验证码错误");
        }
        if (!StringUtils.hasText(user.getEmail())) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_BOUND,
                    "该手机号账号未绑定邮箱，请先登录并在个人中心绑定邮箱后再找回密码");
        }
        return normalizeEmail(user.getEmail());
    }

    /** 单 IP 注册计数（内存态，按自然日重置） */
    private static final class IpRegisterCounter {
        private volatile LocalDate day;
        private final AtomicInteger count = new AtomicInteger(0);

        private IpRegisterCounter(LocalDate day) {
            this.day = day;
        }

        private LocalDate getDay() {
            return day;
        }

        private AtomicInteger getCount() {
            return count;
        }

        private synchronized void reset(LocalDate newDay) {
            this.day = newDay;
            this.count.set(0);
        }

        private void increment() {
            count.incrementAndGet();
        }
    }

    @Override
    public void logout() {
        // Stateless JWT - client discards token
        log.debug("User logged out");
    }

    private void mergeCart(String sessionToken, java.util.UUID userId) {
        List<CartItem> guestItems = cartItemRepository.findBySessionToken(sessionToken);
        for (CartItem guestItem : guestItems) {
            Optional<CartItem> existing = cartItemRepository
                    .findByUserIdAndProductIdAndSpecId(userId, guestItem.getProductId(), guestItem.getSpecId());
            if (existing.isPresent()) {
                // Merge quantity into user's existing item, then delete the guest item
                existing.get().setQuantity(existing.get().getQuantity() + guestItem.getQuantity());
                cartItemRepository.save(existing.get());
                cartItemRepository.delete(guestItem);
            } else {
                // Reassign guest item to user
                guestItem.setUserId(userId);
                guestItem.setSessionToken(null);
                cartItemRepository.save(guestItem);
            }
        }
    }
}
