package com.orionkey.controller;

import com.orionkey.annotation.LogOperation;
import com.orionkey.common.ApiResponse;
import com.orionkey.service.EmailService;
import com.orionkey.service.SiteConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/admin/site-config")
@RequiredArgsConstructor
public class AdminSiteConfigController {

    private final SiteConfigService siteConfigService;
    private final EmailService emailService;

    @GetMapping
    public ApiResponse<?> getAllConfigs() {
        return ApiResponse.success(siteConfigService.getAllConfigs());
    }

    @LogOperation(action = "config.update", targetType = "SITE_CONFIG", detail = "'更新配置'")
    @SuppressWarnings("unchecked")
    @PutMapping
    public ApiResponse<Void> updateConfigs(@RequestBody Map<String, Object> request) {
        List<Map<String, String>> configs = (List<Map<String, String>>) request.get("configs");
        siteConfigService.updateConfigs(configs);
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
}
