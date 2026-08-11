package com.orionkey.service.impl;

import com.orionkey.constant.ErrorCode;
import com.orionkey.constant.UserRole;
import com.orionkey.entity.CartItem;
import com.orionkey.entity.User;
import com.orionkey.exception.BusinessException;
import com.orionkey.model.request.LoginRequest;
import com.orionkey.model.request.RegisterRequest;
import com.orionkey.model.response.AuthResponse;
import com.orionkey.model.response.CaptchaResponse;
import com.orionkey.model.response.UserProfileResponse;
import com.orionkey.repository.CartItemRepository;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final CartItemRepository cartItemRepository;
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
