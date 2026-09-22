package com.orionkey.util;

import java.util.Locale;

/**
 * 根据 User-Agent 识别下单设备/来源。
 * <p>
 * 两套返回值语义不同，不可混用：
 * <ul>
 *   <li>{@link #parseDevice(String)}：中文展示标签（如「微信」「PC浏览器 Chrome」），用于后台订单列表"设备"字段；</li>
 *   <li>{@link #normalizePayDevice(String)} / {@link #labelToPayDevice(String)}：支付路由标识
 *       （wechat / alipay / mobile / pc），与前端 detectPaymentDevice() 保持一致。</li>
 * </ul>
 */
public final class UserAgentUtil {

    /** 支付路由设备标识：微信内置浏览器 */
    public static final String DEVICE_WECHAT = "wechat";
    /** 支付路由设备标识：支付宝内置浏览器 */
    public static final String DEVICE_ALIPAY = "alipay";
    /** 支付路由设备标识：手机浏览器 */
    public static final String DEVICE_MOBILE = "mobile";
    /** 支付路由设备标识：PC 浏览器 */
    public static final String DEVICE_PC = "pc";

    private UserAgentUtil() {
    }

    /**
     * 归一化调用方传入的设备标识（wechat / alipay / mobile / pc）。
     * 无法识别时返回 null，由调用方决定回退策略。
     */
    public static String normalizePayDevice(String device) {
        if (device == null || device.isBlank()) {
            return null;
        }
        return switch (device.trim().toLowerCase(Locale.ROOT)) {
            case DEVICE_WECHAT -> DEVICE_WECHAT;
            case DEVICE_ALIPAY -> DEVICE_ALIPAY;
            case DEVICE_MOBILE -> DEVICE_MOBILE;
            case DEVICE_PC -> DEVICE_PC;
            default -> null;
        };
    }

    /**
     * 展示标签（{@link #parseDevice} 的产物）→ 支付路由设备标识。
     * 用于请求体未携带设备标识、仅能依赖 User-Agent 时的兜底；无法识别时返回 null。
     */
    public static String labelToPayDevice(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        if (label.contains("微信")) {
            // 含「企业微信」
            return DEVICE_WECHAT;
        }
        if (label.contains("支付宝")) {
            return DEVICE_ALIPAY;
        }
        if (label.startsWith("手机浏览器")) {
            return DEVICE_MOBILE;
        }
        if (label.startsWith("PC浏览器")) {
            return DEVICE_PC;
        }
        return null;
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
