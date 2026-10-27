package com.orionkey.service.impl;

import com.orionkey.constant.ErrorCode;
import com.orionkey.constant.UserRole;
import com.orionkey.context.RequestContext;
import com.orionkey.entity.SmsVerificationCode;
import com.orionkey.entity.User;
import com.orionkey.exception.BusinessException;
import com.orionkey.model.response.SendCodeResponse;
import com.orionkey.repository.SmsVerificationCodeRepository;
import com.orionkey.repository.UserRepository;
import com.orionkey.service.OperationLogService;
import com.orionkey.service.SiteConfigService;
import com.orionkey.service.SmsService;
import com.orionkey.util.PhoneUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * 手机短信验证码：频控（发送间隔 / 单号每日上限）与一次性消费。
 * <p>
 * 场景：
 * <ul>
 *   <li>LOGIN：验证码登录（需 sms_enabled + phone_code_login_enabled）；</li>
 *   <li>PASSWORD_RESET：短信找回密码（需 sms_enabled + phone_reset_by_sms_enabled）；</li>
 *   <li>BIND_PHONE：绑定手机号（需 sms_enabled）。</li>
 * </ul>
 * 登录 / 找回密码场景对未注册手机号静默返回成功（防用户枚举）；短信发送失败时抛异常回滚，避免留下脏验证码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsCodeService {

    public static final String SCENE_LOGIN = "LOGIN";
    public static final String SCENE_PASSWORD_RESET = "PASSWORD_RESET";
    public static final String SCENE_BIND_PHONE = "BIND_PHONE";

    /** 单个验证码最大校验次数，超过则作废需重新获取 */
    private static final int MAX_CODE_ATTEMPTS = 5;
    private static final SecureRandom CODE_RANDOM = new SecureRandom();
    /** 操作日志 action：验证码短信发送（成功 / 失败均留痕，用于统计失败率） */
    private static final String ACTION_SMS_CODE_SEND = "sms.code.send";
    /** 匿名操作占位用户（operation_logs.user_id 非空，未登录发码时使用全零 UUID） */
    private static final UUID ANONYMOUS_LOG_USER_ID = new UUID(0L, 0L);

    private final SmsVerificationCodeRepository smsVerificationCodeRepository;
    private final UserRepository userRepository;
    private final SiteConfigService siteConfigService;
    private final SmsService smsService;
    private final OperationLogService operationLogService;

    /**
     * 发送验证码短信。
     *
     * @param rawPhone 用户输入的手机号（可为 +86 / 带分隔符）
     * @param rawScene 场景（LOGIN / PASSWORD_RESET / BIND_PHONE）
     * @param ip       请求方 IP（仅记录，用于审计）
     */
    @Transactional
    public SendCodeResponse sendCode(String rawPhone, String rawScene, String ip) {
        if (!smsService.isEnabled()) {
            throw new BusinessException(ErrorCode.SMS_NOT_ENABLED, "短信服务未启用");
        }
        String scene = rawScene == null ? "" : rawScene.trim().toUpperCase(Locale.ROOT);
        if (!SCENE_LOGIN.equals(scene) && !SCENE_PASSWORD_RESET.equals(scene) && !SCENE_BIND_PHONE.equals(scene)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的验证码场景");
        }
        String phone = PhoneUtils.normalize(rawPhone);
        if (!PhoneUtils.isValidCnMobile(phone)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号格式不正确");
        }

        if (SCENE_LOGIN.equals(scene) && !isConfigEnabled("phone_code_login_enabled")) {
            throw new BusinessException(ErrorCode.PHONE_FEATURE_DISABLED, "手机号验证码登录未启用");
        }
        if (SCENE_PASSWORD_RESET.equals(scene) && !isConfigEnabled("phone_reset_by_sms_enabled")) {
            throw new BusinessException(ErrorCode.PHONE_FEATURE_DISABLED, "手机号短信找回密码未启用");
        }

        int expireMinutes = intConfig("phone_code_expire_minutes", 10);
        int intervalSeconds = intConfig("phone_code_interval_seconds", 60);
        int dailyLimit = intConfig("phone_code_daily_limit", 10);

        // 防用户枚举：登录 / 找回密码场景，手机号未注册或已禁用时静默返回成功（不发送、不落库）
        if (!SCENE_BIND_PHONE.equals(scene)) {
            User user = userRepository.findByPhone(phone).orElse(null);
            if (user == null || user.getIsDeleted() == 1) {
                log.info("Skip sending sms code (phone not registered): scene={}", scene);
                return new SendCodeResponse(expireMinutes, intervalSeconds);
            }
            if (SCENE_LOGIN.equals(scene) && user.getRole() == UserRole.ADMIN) {
                throw new BusinessException(ErrorCode.ADMIN_PHONE_LOGIN_FORBIDDEN, "管理员请使用账号或邮箱登录");
            }
        }

        LocalDateTime now = LocalDateTime.now();

        // 重发间隔限制（以最近一次发送时间为准，无论是否已使用）
        SmsVerificationCode last = smsVerificationCodeRepository
                .findTopByPhoneAndSceneOrderByCreatedAtDesc(phone, scene).orElse(null);
        if (last != null && last.getCreatedAt() != null
                && last.getCreatedAt().plusSeconds(intervalSeconds).isAfter(now)) {
            long wait = Duration.between(now, last.getCreatedAt().plusSeconds(intervalSeconds)).getSeconds() + 1;
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "请求过于频繁，请 " + wait + " 秒后再试");
        }

        // 单号每日发送上限
        long sentToday = smsVerificationCodeRepository.countByPhoneAndSceneAndCreatedAtGreaterThanEqual(
                phone, scene, now.toLocalDate().atStartOfDay());
        if (sentToday >= dailyLimit) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "今日验证码发送次数已达上限，请明天再试");
        }

        // 同场景旧未用码作废，并清理历史过期码
        smsVerificationCodeRepository.findTopByPhoneAndSceneAndUsedFalseOrderByCreatedAtDesc(phone, scene)
                .ifPresent(old -> {
                    old.setUsed(true);
                    smsVerificationCodeRepository.save(old);
                });
        smsVerificationCodeRepository.deleteByPhoneAndSceneAndExpiresAtBefore(phone, scene, now.minusDays(1));

        String code = String.format("%06d", CODE_RANDOM.nextInt(1_000_000));
        SmsVerificationCode record = new SmsVerificationCode();
        record.setPhone(phone);
        record.setScene(scene);
        record.setCode(code);
        record.setExpiresAt(now.plusMinutes(expireMinutes));
        record.setUsed(false);
        record.setAttemptCount(0);
        record.setIp(ip);
        smsVerificationCodeRepository.save(record);

        try {
            smsService.sendVerifyCode(phone, scene, code, expireMinutes);
        } catch (Exception e) {
            log.error("Send sms code failed: scene={}, err={}", scene, e.getMessage());
            recordSendLog(scene, phone, ip, "failed", e.getMessage());
            // 抛异常触发事务回滚，避免留下「已发码但短信未送达」的脏记录
            throw e instanceof BusinessException be
                    ? be
                    : new BusinessException(ErrorCode.SMS_SEND_FAILED, "验证码发送失败，请稍后重试");
        }

        recordSendLog(scene, phone, ip, "success", null);
        log.info("Sms code sent: scene={}, expireMinutes={}", scene, expireMinutes);
        return new SendCodeResponse(expireMinutes, intervalSeconds);
    }

    /** 记录验证码短信发送日志（独立事务，失败时不阻断主流程；手机号脱敏） */
    private void recordSendLog(String scene, String phone, String ip, String result, String error) {
        try {
            UUID userId = RequestContext.getUserId();
            RequestContext.UserInfo userInfo = RequestContext.get();
            String username = userInfo != null ? userInfo.getUsername() : "anonymous";
            StringBuilder detail = new StringBuilder("scene=").append(scene)
                    .append(";phone=").append(maskPhone(phone))
                    .append(";result=").append(result);
            if (error != null && !error.isBlank()) {
                detail.append(";error=").append(error);
            }
            operationLogService.logInNewTransaction(userId != null ? userId : ANONYMOUS_LOG_USER_ID,
                    username, ACTION_SMS_CODE_SEND, "SMS", null, detail.toString(), ip);
        } catch (Exception e) {
            log.warn("Failed to record sms code send log: {}", e.getMessage());
        }
    }

    /** 手机号脱敏：保留前 3 位与后 4 位 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 校验并一次性消费验证码：不存在/已过期直接失败；校验失败累加次数，达到上限作废；
     * 校验成功标记已用，防止重放。调用方需以 {@code noRollbackFor = BusinessException.class}
     * 保证失败时的次数/作废状态能落库。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public void verifyAndConsume(String phone, String scene, String inputCode) {
        SmsVerificationCode record = smsVerificationCodeRepository
                .findTopByPhoneAndSceneAndUsedFalseOrderByCreatedAtDesc(phone, scene)
                .orElseThrow(() -> new BusinessException(ErrorCode.CAPTCHA_INVALID, "验证码错误或已过期"));
        if (record.getExpiresAt() == null || record.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID, "验证码错误或已过期");
        }
        if (record.getAttemptCount() >= MAX_CODE_ATTEMPTS) {
            record.setUsed(true);
            smsVerificationCodeRepository.save(record);
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID, "验证码错误次数过多，请重新获取");
        }
        if (inputCode == null || !inputCode.trim().equals(record.getCode())) {
            record.setAttemptCount(record.getAttemptCount() + 1);
            if (record.getAttemptCount() >= MAX_CODE_ATTEMPTS) {
                record.setUsed(true);
            }
            smsVerificationCodeRepository.save(record);
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID, "验证码错误或已过期");
        }
        record.setUsed(true);
        smsVerificationCodeRepository.save(record);
    }

    /** 读取整数型站点配置，缺失或非法时回退默认值 */
    private int intConfig(String key, int fallback) {
        int value = siteConfigService.getIntValue(key, fallback);
        return value > 0 ? value : fallback;
    }

    /** 布尔开关：配置值为 true（忽略大小写）视为开启 */
    private boolean isConfigEnabled(String key) {
        return "true".equalsIgnoreCase(siteConfigService.getValue(key, "false").trim());
    }
}
