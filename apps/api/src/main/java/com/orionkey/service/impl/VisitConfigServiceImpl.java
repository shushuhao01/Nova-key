package com.orionkey.service.impl;

import com.orionkey.entity.SiteConfig;
import com.orionkey.repository.SiteConfigRepository;
import com.orionkey.service.VisitConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VisitConfigServiceImpl implements VisitConfigService {

    private static final String KEY_TRACK_ENABLED = "visit_track_enabled";
    private static final String KEY_EXCLUDE_ADMIN = "visit_exclude_admin";
    private static final String KEY_EXCLUDE_BOT = "visit_exclude_bot";
    private static final String KEY_RETENTION_DAYS = "visit_retention_days";
    private static final String KEY_GEO_ENABLED = "visit_geolocation_enabled";
    private static final String KEY_XDB_PATH = "visit_ip_xdb_path";
    private static final String KEY_WHITELIST = "visit_ip_whitelist";
    private static final String KEY_BLACKLIST = "visit_ip_blacklist";

    private static final String GROUP = "visit";

    private final SiteConfigRepository siteConfigRepository;

    private String raw(String key, String defaultValue) {
        return siteConfigRepository.findByConfigKey(key)
                .map(SiteConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(defaultValue);
    }

    private boolean bool(String key, boolean defaultValue) {
        String v = siteConfigRepository.findByConfigKey(key).map(SiteConfig::getConfigValue).orElse(null);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    private int integer(String key, int defaultValue) {
        String v = siteConfigRepository.findByConfigKey(key).map(SiteConfig::getConfigValue).orElse(null);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public boolean trackEnabled() {
        return bool(KEY_TRACK_ENABLED, true);
    }

    @Override
    public boolean excludeAdmin() {
        return bool(KEY_EXCLUDE_ADMIN, true);
    }

    @Override
    public boolean excludeBot() {
        return bool(KEY_EXCLUDE_BOT, true);
    }

    @Override
    public int retentionDays() {
        int days = integer(KEY_RETENTION_DAYS, 90);
        return days < 1 ? 1 : days;
    }

    @Override
    public boolean geolocationEnabled() {
        return bool(KEY_GEO_ENABLED, true);
    }

    @Override
    public String xdbPath() {
        return raw(KEY_XDB_PATH, "./data/ip2region.xdb");
    }

    @Override
    public List<String> ipWhitelist() {
        return split(raw(KEY_WHITELIST, ""));
    }

    @Override
    public List<String> ipBlacklist() {
        return split(raw(KEY_BLACKLIST, ""));
    }

    @Override
    public Map<String, Object> getConfig() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(KEY_TRACK_ENABLED, trackEnabled());
        map.put(KEY_EXCLUDE_ADMIN, excludeAdmin());
        map.put(KEY_EXCLUDE_BOT, excludeBot());
        map.put(KEY_RETENTION_DAYS, retentionDays());
        map.put(KEY_GEO_ENABLED, geolocationEnabled());
        map.put(KEY_XDB_PATH, xdbPath());
        map.put(KEY_WHITELIST, raw(KEY_WHITELIST, ""));
        map.put(KEY_BLACKLIST, raw(KEY_BLACKLIST, ""));
        return map;
    }

    @Override
    @Transactional
    public void updateConfig(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            Object value = entry.getValue();
            String str = value == null ? "" : String.valueOf(value);
            save(key, str);
        }
    }

    private void save(String key, String value) {
        SiteConfig config = siteConfigRepository.findByConfigKey(key)
                .orElseGet(() -> {
                    SiteConfig c = new SiteConfig();
                    c.setConfigKey(key);
                    c.setConfigGroup(GROUP);
                    return c;
                });
        config.setConfigValue(value);
        siteConfigRepository.save(config);
    }

    private static List<String> split(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return list;
        }
        for (String part : raw.split("[,\\n\\r]+")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                list.add(t);
            }
        }
        return list;
    }
}
