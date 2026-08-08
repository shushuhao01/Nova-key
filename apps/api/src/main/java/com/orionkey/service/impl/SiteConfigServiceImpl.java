package com.orionkey.service.impl;

import com.orionkey.entity.SiteConfig;
import com.orionkey.repository.SiteConfigRepository;
import com.orionkey.service.SiteConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SiteConfigServiceImpl implements SiteConfigService {

    private final SiteConfigRepository siteConfigRepository;

    @org.springframework.beans.factory.annotation.Value("${turnstile.site-key:}")
    private String turnstileSiteKey;

    private static final Set<String> NUMERIC_KEYS = Set.of("points_rate");

    private static final List<String> PUBLIC_KEYS = List.of(
            "site_name", "site_slogan", "site_description", "logo_url", "favicon_url",
            "announcement_enabled", "announcement", "popup_enabled", "popup_content",
            "contact_email", "contact_telegram", "contact_telegram_group", "contact_phone", "contact_address",
            "wechat_kefu_link", "wechat_qrcode",
            "points_enabled", "points_rate",
            "maintenance_enabled", "maintenance_message", "footer_text", "github_url", "custom_css",
            "copyright", "icp_number", "police_number"
    );

    /** 系统版本号：仅在管理后台「网站设置 → 基本信息」只读展示 */
    private static final String SYSTEM_VERSION = "1.0.0";

    /** F16: 管理员允许编辑的配置键白名单 — 防止写入系统内部键或注入任意配置 */
    private static final Set<String> EDITABLE_KEYS = Set.of(
            // 站点基础
            "site_name", "site_slogan", "site_description", "logo_url", "favicon_url",
            // 公告 / 弹窗
            "announcement_enabled", "announcement", "popup_enabled", "popup_content",
            // 联系方式
            "contact_email", "contact_telegram", "contact_telegram_group", "contact_phone", "contact_address",
            "wechat_kefu_link", "wechat_qrcode",
            // 版权 / 备案
            "copyright", "icp_number", "police_number",
            // 积分
            "points_enabled", "points_rate",
            // 维护模式
            "maintenance_enabled", "maintenance_message",
            // 页脚 / 外链
            "footer_text", "github_url",
            // 自定义样式
            "custom_css",
            // 邮箱发件（SMTP）— 供管理后台「网站设置 → 邮箱发件」配置
            "mail_enabled", "smtp_host", "smtp_port", "smtp_username", "smtp_password",
            "mail_from", "mail_from_name", "mail_site_url",
            // 系统参数
            "order_expire_minutes", "max_pending_orders_per_user", "max_pending_orders_per_ip",
            "rate_limit_per_second"
    );

    /** F15: CSS 危险模式 — 用于过滤 custom_css 中的 XSS 向量 */
    private static final Pattern CSS_DANGEROUS_PATTERNS = Pattern.compile(
            "(?i)(expression\\s*\\(|javascript\\s*:|@import\\s|\\\\00|behavior\\s*:|" +
            "-moz-binding\\s*:|url\\s*\\(\\s*[\"']?\\s*javascript)",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public Map<String, Object> getPublicConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : PUBLIC_KEYS) {
            siteConfigRepository.findByConfigKey(key).ifPresent(c -> {
                String val = c.getConfigValue();
                if ("true".equalsIgnoreCase(val) || "false".equalsIgnoreCase(val)) {
                    result.put(key, Boolean.parseBoolean(val));
                } else if (NUMERIC_KEYS.contains(key)) {
                    try {
                        result.put(key, Integer.parseInt(val));
                    } catch (NumberFormatException e) {
                        result.put(key, val);
                    }
                } else {
                    result.put(key, val);
                }
            });
        }
        // F15: 对 custom_css 进行安全过滤，防止存储型 XSS
        if (result.containsKey("custom_css") && result.get("custom_css") instanceof String css) {
            result.put("custom_css", sanitizeCss(css));
        }
        // Turnstile Site Key：仅在后台开关启用时才返回给前端，确保前后端状态一致
        boolean turnstileEnabled = siteConfigRepository.findByConfigKey("turnstile_enabled")
                .map(c -> "true".equalsIgnoreCase(c.getConfigValue()))
                .orElse(false);
        if (turnstileEnabled && turnstileSiteKey != null && !turnstileSiteKey.isBlank()) {
            result.put("turnstile_site_key", turnstileSiteKey);
        }
        return result;
    }

    @Override
    public List<?> getAllConfigs() {
        List<Map<String, Object>> list = new ArrayList<>(siteConfigRepository.findAll().stream()
                .map(c -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("config_key", c.getConfigKey());
                    String value = c.getConfigValue();
                    // SMTP 密码不回传明文，已配置时标记 __SET__（前端显示"已设置，留空则不修改"）
                    if ("smtp_password".equals(c.getConfigKey()) && value != null && !value.isBlank()) {
                        value = "__SET__";
                    }
                    map.put("config_value", value);
                    map.put("config_group", c.getConfigGroup());
                    return map;
                }).toList());
        // 系统版本号：只读展示，非数据库配置项，追加在末尾
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("config_key", "system_version");
        version.put("config_value", SYSTEM_VERSION);
        version.put("config_group", "site");
        version.put("readonly", true);
        list.add(version);
        return list;
    }

    @Override
    @Transactional
    public void updateConfigs(List<Map<String, String>> configs) {
        for (Map<String, String> item : configs) {
            String key = item.get("config_key");
            String value = item.get("config_value");
            // F16: 只允许白名单内的 key 被修改，防止注入系统内部配置
            if (key == null || !EDITABLE_KEYS.contains(key)) {
                log.warn("Rejected config update for non-editable key: {}", key);
                continue;
            }
            // SMTP 密码：前端空值或 __SET__ 表示不修改，保留原密码
            if ("smtp_password".equals(key) && (value == null || value.isBlank() || "__SET__".equals(value))) {
                continue;
            }
            // F15: custom_css 写入时也做安全过滤
            if ("custom_css".equals(key) && value != null) {
                value = sanitizeCss(value);
            }
            SiteConfig config = siteConfigRepository.findByConfigKey(key)
                    .orElseGet(() -> {
                        SiteConfig c = new SiteConfig();
                        c.setConfigKey(key);
                        return c;
                    });
            config.setConfigValue(value);
            siteConfigRepository.save(config);
        }
    }

    @Override
    @Transactional
    public void toggleMaintenance(boolean enabled) {
        SiteConfig config = siteConfigRepository.findByConfigKey("maintenance_enabled")
                .orElseGet(() -> {
                    SiteConfig c = new SiteConfig();
                    c.setConfigKey("maintenance_enabled");
                    c.setConfigGroup("site");
                    return c;
                });
        config.setConfigValue(String.valueOf(enabled));
        siteConfigRepository.save(config);
    }

    /**
     * 过滤 CSS 中的危险内容，防止存储型 XSS。
     * 移除 HTML 标签和已知 CSS XSS 向量（expression/javascript:/behavior 等）。
     */
    private String sanitizeCss(String css) {
        if (css == null) return null;
        // 移除所有 HTML 标签（防止 </style><script>... 注入）
        css = css.replaceAll("<[^>]*>", "");
        // 移除危险 CSS 模式
        css = CSS_DANGEROUS_PATTERNS.matcher(css).replaceAll("/* blocked */");
        return css;
    }
}
