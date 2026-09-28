package com.orionkey.service.impl;

import com.orionkey.constant.ErrorCode;
import com.orionkey.constant.UserRole;
import com.orionkey.entity.CartItem;
import com.orionkey.entity.EmailVerificationCode;
import com.orionkey.entity.SiteConfig;
import com.orionkey.entity.User;
import com.orionkey.exception.BusinessException;
import com.orionkey.model.request.CodeLoginRequest;
import com.orionkey.model.request.LoginRequest;
import com.orionkey.model.request.RegisterRequest;
import com.orionkey.model.request.ResetPasswordRequest;
import com.orionkey.model.request.SendCodeRequest;
import com.orionkey.model.response.AuthResponse;
import com.orionkey.model.response.CaptchaResponse;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
        // 登录账号是邮箱时统一小写（与注册时的邮箱小写规则一致），用户名则原样
        String account = request.getAccount();
        if (account != null && account.contains("@")) {
            account = account.trim().toLowerCase();
        }
        User user = userRepository.findByUsernameOrEmail(account, account)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS, "用户名或密码错误"));

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

        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole().name(), user.getPasswordVersion());
        return new AuthResponse(token, UserProfileResponse.from(user, permissionResolver.resolve(user)));
    }

    // ═══════════ 邮箱验证码：发送 / 忘记密码 / 验证码登录 ═══════════

    @Override
    @Transactional
    public SendCodeResponse sendEmailCode(SendCodeRequest request, String ip) {
        String email = normalizeEmail(request.getEmail());
        String scene = request.getScene() == null ? "" : request.getScene().trim().toUpperCase(Locale.ROOT);
        if (!SCENE_PASSWORD_RESET.equals(scene) && !SCENE_LOGIN.equals(scene)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的验证码场景");
        }

        int expireMinutes = intConfig("auth_code_expire_minutes", DEFAULT_CODE_EXPIRE_MINUTES);
        int intervalSeconds = intConfig("auth_code_send_interval_seconds", DEFAULT_CODE_SEND_INTERVAL_SECONDS);
        int dailyLimit = intConfig("auth_code_daily_limit", DEFAULT_CODE_DAILY_LIMIT);
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
        String email = normalizeEmail(request.getEmail());
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
