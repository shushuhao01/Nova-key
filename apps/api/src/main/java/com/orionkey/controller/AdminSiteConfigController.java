package com.orionkey.controller;

import com.orionkey.annotation.LogOperation;
import com.orionkey.common.ApiResponse;
import com.orionkey.context.RequestContext;
import com.orionkey.service.EmailService;
import com.orionkey.service.MailBounceService;
import com.orionkey.service.OperationLogService;
import com.orionkey.service.SiteConfigService;
import com.orionkey.service.SmsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/admin/site-config")
@RequiredArgsConstructor
public class AdminSiteConfigController {

    private final SiteConfigService siteConfigService;
    private final EmailService emailService;
    private final SmsService smsService;
    private final OperationLogService operationLogService;
    private final MailBounceService mailBounceService;

    @GetMapping
    public ApiResponse<?> getAllConfigs() {
        return ApiResponse.success(siteConfigService.getAllConfigs());
    }

    /**
     * 通用配置保存：客户配置（config_group = customer）与站点配置分别记日志，
     * 便于在操作日志中区分「更新客户配置」与「更新站点配置」。
     */
    @SuppressWarnings("unchecked")
    @PutMapping
    public ApiResponse<Void> updateConfigs(@RequestBody Map<String, Object> request) {
        List<Map<String, String>> configs = (List<Map<String, String>>) request.get("configs");
        Map<String, Map<String, Object>> before = snapshotConfigs();
        siteConfigService.updateConfigs(configs);
        recordConfigUpdateLogs(configs, before);
        return ApiResponse.success();
    }

    @LogOperation(action = "config.update", targetType = "SITE_CONFIG", detail = "'切换维护模式'")
    @PostMapping("/maintenance")
    public ApiResponse<Void> toggleMaintenance(@RequestBody Map<String, Object> request) {
        boolean enabled = (boolean) request.get("enabled");
        siteConfigService.toggleMaintenance(enabled);
        return ApiResponse.success();
    }

    /** 测试邮件发送：用当前 SMTP 配置向指定邮箱发一封测试邮件 */
    @LogOperation(action = "config.update", targetType = "SITE_CONFIG", detail = "'发送测试邮件'")
    @PostMapping("/email/test")
    public ApiResponse<Void> testEmail(@RequestBody Map<String, Object> request) {
        String toEmail = request.get("to_email") != null ? request.get("to_email").toString() : "";
        if (toEmail.isBlank()) {
            return ApiResponse.error(400, "请输入测试收件邮箱");
        }
        try {
            emailService.sendTestEmail(toEmail.trim());
            return ApiResponse.success("测试邮件已发送，请查收", null);
        } catch (Exception e) {
            log.error("Test email send failed: {}", e.getMessage());
            return ApiResponse.error(500, "测试邮件发送失败：" + e.getMessage());
        }
    }

    /** 查看已保存的邮箱密码明文（仅限邮箱密码类配置项，供管理员查看/复制） */
    @LogOperation(action = "config.update", targetType = "SITE_CONFIG", detail = "'查看邮件密码明文'")
    @GetMapping("/secret")
    public ApiResponse<Map<String, Object>> revealSecret(@RequestParam("key") String key) {
        if (!"smtp_password".equals(key) && !"imap_password".equals(key)) {
            return ApiResponse.error(400, "不支持的配置项");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", key);
        data.put("value", siteConfigService.getValue(key, ""));
        return ApiResponse.success(data);
    }

    /** 立即拉取退信邮箱（IMAP），解析 DSN 退信并回写收件人退信状态 */
    @LogOperation(action = "config.update", targetType = "SITE_CONFIG", detail = "'检查退信邮箱'")
    @PostMapping("/email/imap-check")
    public ApiResponse<Map<String, Object>> checkBounces() {
        if (!mailBounceService.isEnabled()) {
            return ApiResponse.error(400, "请先开启退信检测并保存 IMAP 配置");
        }
        try {
            int processed = mailBounceService.pollBounces();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("processed", processed);
            return ApiResponse.success("本次处理退信 " + processed + " 个", data);
        } catch (Exception e) {
            log.error("Check bounce mailbox failed: {}", e.getMessage());
            return ApiResponse.error(500, "检查退信失败：" + e.getMessage());
        }
    }

    /** 读取短信配置（config_group = sms；AccessKeySecret 已脱敏为 __SET__） */
    @GetMapping("/sms")
    @SuppressWarnings("unchecked")
    public ApiResponse<?> getSmsConfigs() {
        List<Map<String, Object>> smsConfigs = new ArrayList<>();
        for (Object item : siteConfigService.getAllConfigs()) {
            if (item instanceof Map<?, ?> map && "sms".equals(map.get("config_group"))) {
                smsConfigs.add((Map<String, Object>) map);
            }
        }
        return ApiResponse.success(smsConfigs);
    }

    /** 保存短信配置：启用时强校验 AK / SK / 签名 / 通用验证码模板 */
    @LogOperation(action = "config.sms.update", targetType = "SITE_CONFIG", detail = "'更新短信配置'")
    @SuppressWarnings("unchecked")
    @PutMapping("/sms")
    public ApiResponse<Void> updateSmsConfigs(@RequestBody Map<String, Object> request) {
        Object raw = request.get("configs");
        List<Map<String, String>> configs = raw instanceof List<?> ? (List<Map<String, String>>) raw : null;
        if (configs == null || configs.isEmpty()) {
            return ApiResponse.error(400, "没有需要保存的短信配置");
        }
        Map<String, String> incoming = new HashMap<>();
        for (Map<String, String> item : configs) {
            String key = item.get("config_key");
            if (key == null || key.isBlank()) continue;
            if (!key.startsWith("sms_")) {
                return ApiResponse.error(400, "仅允许修改短信配置项：" + key);
            }
            incoming.put(key, item.get("config_value"));
        }
        if ("true".equalsIgnoreCase(effectiveSmsValue(incoming, "sms_enabled"))) {
            List<String> missing = new ArrayList<>();
            if (effectiveSmsValue(incoming, "sms_access_key_id").isBlank()) missing.add("AccessKey ID");
            if (effectiveSmsValue(incoming, "sms_access_key_secret").isBlank()) missing.add("AccessKey Secret");
            if (effectiveSmsValue(incoming, "sms_sign_name").isBlank()) missing.add("短信签名");
            if (effectiveSmsValue(incoming, "sms_tpl_verify_code").isBlank()) missing.add("通用验证码模板 CODE");
            if (!missing.isEmpty()) {
                return ApiResponse.error(400, "启用短信服务前请先填写：" + String.join("、", missing));
            }
        }
        siteConfigService.updateConfigs(configs);
        return ApiResponse.success();
    }

    /** 测试连通：校验 AK/SK（只读接口，权限不足时退化为发一条测试短信） */
    @LogOperation(action = "config.sms.test", targetType = "SITE_CONFIG", detail = "'测试短信连通'")
    @PostMapping("/sms/test-connection")
    public ApiResponse<Map<String, Object>> testSmsConnection() {
        Map<String, Object> result = smsService.testConnection();
        return ApiResponse.success(result);
    }

    /** 测试发送：向指定手机号发送指定模板短信（template_code 为空时用通用验证码模板） */
    @LogOperation(action = "config.sms.test", targetType = "SITE_CONFIG", detail = "'发送测试短信'")
    @PostMapping("/sms/test-send")
    public ApiResponse<Map<String, Object>> testSmsSend(@RequestBody Map<String, Object> request) {
        String phone = request.get("phone") != null ? request.get("phone").toString().trim() : "";
        String templateCode = request.get("template_code") != null ? request.get("template_code").toString().trim() : "";
        if (phone.isBlank()) {
            return ApiResponse.error(400, "请输入测试接收手机号");
        }
        return ApiResponse.success(smsService.testSend(phone, templateCode));
    }

    /** 取本次提交值，空值 / __SET__ 时回退到数据库已存值 */
    private String effectiveSmsValue(Map<String, String> incoming, String key) {
        String value = incoming.get(key);
        if (value != null && !value.isBlank() && !"__SET__".equals(value)) {
            return value.trim();
        }
        String stored = siteConfigService.getValue(key, "");
        return stored == null ? "" : stored.trim();
    }

    /** 保存前的配置快照：config_key -> 原始配置项（含 config_value / config_group） */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> snapshotConfigs() {
        Map<String, Map<String, Object>> snapshot = new HashMap<>();
        for (Object item : siteConfigService.getAllConfigs()) {
            if (item instanceof Map<?, ?> map) {
                Object key = map.get("config_key");
                if (key != null) {
                    snapshot.put(key.toString(), (Map<String, Object>) map);
                }
            }
        }
        return snapshot;
    }

    /** 对比提交值与保存前快照，按配置分组分别记录操作日志 */
    private void recordConfigUpdateLogs(List<Map<String, String>> configs,
                                        Map<String, Map<String, Object>> before) {
        if (configs == null || configs.isEmpty()) {
            return;
        }
        Set<String> customerKeys = new LinkedHashSet<>();
        Set<String> siteKeys = new LinkedHashSet<>();
        for (Map<String, String> item : configs) {
            String key = item.get("config_key");
            if (key == null || key.isBlank() || "system_version".equals(key)) {
                continue;
            }
            Map<String, Object> stored = before.get(key);
            String group = stored != null ? Objects.toString(stored.get("config_group"), "") : "";
            // 短信配置由专用接口 /admin/site-config/sms 记录 config.sms.update
            if ("sms".equals(group)) {
                continue;
            }
            String incomingValue = item.get("config_value");
            if ("smtp_password".equals(key) || "imap_password".equals(key)
                    || "sms_access_key_secret".equals(key)) {
                // 脱敏占位或空值表示未修改
                if (!StringUtils.hasText(incomingValue) || "__SET__".equals(incomingValue)) {
                    continue;
                }
            }
            String storedValue = stored != null ? Objects.toString(stored.get("config_value"), "") : "";
            if (Objects.equals(incomingValue == null ? "" : incomingValue, storedValue)) {
                continue;
            }
            if ("customer".equals(group)) {
                customerKeys.add(key);
            } else {
                siteKeys.add(key);
            }
        }
        if (!customerKeys.isEmpty()) {
            writeConfigLog("config.customer.update", "修改客户配置：" + String.join("、", customerKeys));
        }
        if (!siteKeys.isEmpty()) {
            writeConfigLog("config.update", "修改站点配置：" + String.join("、", siteKeys));
        }
    }

    /** 写配置变更日志（失败不影响业务） */
    private void writeConfigLog(String action, String detail) {
        try {
            UUID userId = RequestContext.getUserId();
            RequestContext.UserInfo userInfo = RequestContext.get();
            String username = userInfo != null ? userInfo.getUsername() : "unknown";
            operationLogService.log(userId, username, action, "SITE_CONFIG", null, detail, resolveClientIp());
        } catch (Exception e) {
            log.warn("Failed to record config update log for {}: {}", action, e.getMessage());
        }
    }

    /** 解析客户端 IP（优先 X-Forwarded-For / X-Real-IP） */
    private static String resolveClientIp() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "unknown";
        }
        HttpServletRequest request = attrs.getRequest();
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
