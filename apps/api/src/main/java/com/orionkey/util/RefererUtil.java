package com.orionkey.util;

import java.net.URI;
import java.util.Locale;

/**
 * 来源（Referer）解析：提取域名并归类为 direct / search / social / external。
 */
public final class RefererUtil {

    public static final String SOURCE_DIRECT = "direct";
    public static final String SOURCE_SEARCH = "search";
    public static final String SOURCE_SOCIAL = "social";
    public static final String SOURCE_EXTERNAL = "external";

    private static final String[] SEARCH_HOSTS = {
            "baidu.", "google.", "bing.", "sogou.", "so.com", "360.cn", "sm.cn",
            "yandex", "duckduckgo", "shenma", "haosou", "search."
    };

    private static final String[] SOCIAL_HOSTS = {
            "weibo", "zhihu", "douban", "xiaohongshu", "xhslink", "tieba", "qq.com",
            "weixin", "wechat", "douyin", "bilibili", "twitter", "t.co", "facebook",
            "instagram", "linkedin", "reddit", "tiktok", "youtube", "telegram", "discord"
    };

    private RefererUtil() {
    }

    /**
     * 提取来源域名（不含协议与路径）；无法解析返回 null。
     */
    public static String hostOf(String referer) {
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(referer.trim());
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将来源域名归类。
     */
    public static String classify(String host) {
        if (host == null || host.isBlank()) {
            return SOURCE_DIRECT;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String s : SEARCH_HOSTS) {
            if (h.contains(s)) {
                return SOURCE_SEARCH;
            }
        }
        for (String s : SOCIAL_HOSTS) {
            if (h.contains(s)) {
                return SOURCE_SOCIAL;
            }
        }
        return SOURCE_EXTERNAL;
    }

    /** 来源分类的中文展示标签。 */
    public static String label(String source) {
        if (source == null) {
            return "未知";
        }
        return switch (source) {
            case SOURCE_DIRECT -> "直接访问";
            case SOURCE_SEARCH -> "搜索引擎";
            case SOURCE_SOCIAL -> "社交媒体";
            case SOURCE_EXTERNAL -> "外部链接";
            default -> source;
        };
    }
}
