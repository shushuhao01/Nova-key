package com.orionkey.util;

import java.util.Locale;

/**
 * 根据 User-Agent 识别下单设备/来源，用于管理后台订单列表"设备"字段展示。
 * 统一返回类似：微信、PC浏览器 Chrome、手机浏览器 Safari 等；无法识别时返回 null。
 */
public final class UserAgentUtil {

    private UserAgentUtil() {
    }

    /**
     * 解析 User-Agent 为可读的设备标签。
     *
     * @param userAgent 浏览器 User-Agent（可为 null）
     * @return 设备标签；无法识别返回 null
     */
    public static String parseDevice(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);

        // 微信内置浏览器（含企业微信）
        if (ua.contains("micromessenger")) {
            return ua.contains("wxwork") ? "企业微信" : "微信";
        }
        // 支付宝内置浏览器
        if (ua.contains("alipayclient")) {
            return "支付宝";
        }

        boolean isMobile = ua.contains("mobile")
                || ua.contains("iphone")
                || ua.contains("ipad")
                || ua.contains("android")
                || ua.contains("harmonyos");

        String browser = detectBrowser(ua);
        String terminal = isMobile ? "手机浏览器" : "PC浏览器";
        return browser == null ? terminal : terminal + " " + browser;
    }

    private static String detectBrowser(String ua) {
        if (ua.contains("edg/") || ua.contains("edga") || ua.contains("edgios")) {
            return "Edge";
        }
        if (ua.contains("qqbrowser")) {
            return "QQ浏览器";
        }
        if (ua.contains("ucbrowser")) {
            return "UC浏览器";
        }
        if (ua.contains("firefox/") || ua.contains("fxios")) {
            return "Firefox";
        }
        if (ua.contains("chrome/") || ua.contains("crios")) {
            return "Chrome";
        }
        if (ua.contains("safari/")) {
            return "Safari";
        }
        return null;
    }
}
