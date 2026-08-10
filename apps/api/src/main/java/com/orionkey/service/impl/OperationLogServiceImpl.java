package com.orionkey.service.impl;

import com.orionkey.common.PageResult;
import com.orionkey.entity.OperationLog;
import com.orionkey.entity.SiteConfig;
import com.orionkey.repository.OperationLogRepository;
import com.orionkey.repository.SiteConfigRepository;
import com.orionkey.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogServiceImpl implements OperationLogService {

    /** 定时清理配置键（存于 site_configs） */
    private static final String KEY_CLEANUP_ENABLED = "operation_log_cleanup_enabled";
    private static final String KEY_CLEANUP_HOURS = "operation_log_cleanup_hours";
    /** 默认保留时长：24 小时 */
    private static final int DEFAULT_CLEANUP_HOURS = 24;

    private final OperationLogRepository operationLogRepository;
    private final SiteConfigRepository siteConfigRepository;

    @Override
    public PageResult<?> listLogs(UUID userId, String action, String targetType,
                                   String startDate, String endDate, int page, int pageSize) {
        var pageable = PageRequest.of(page - 1, pageSize);
        LocalDateTime start = startDate != null ? LocalDate.parse(startDate).atStartOfDay() : null;
        LocalDateTime end = endDate != null ? LocalDate.parse(endDate).atTime(LocalTime.MAX) : null;

        Page<OperationLog> logPage = operationLogRepository.findByFilters(userId, action, targetType, start, end, pageable);
        return PageResult.of(logPage, logPage.getContent());
    }

    @Override
    @Transactional
    public void log(UUID userId, String username, String action, String targetType,
                    String targetId, String detail, String ip) {
        OperationLog log = new OperationLog();
        log.setUserId(userId);
        log.setUsername(username);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetail(detail);
        log.setIpAddress(ip);
        operationLogRepository.save(log);
    }

    // ═══════════ 定时清理配置 ═══════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getCleanupConfig() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", isCleanupEnabled());
        out.put("hours", cleanupHours());
        return out;
    }

    @Override
    @Transactional
    public void saveCleanupConfig(boolean enabled, int hours) {
        int safeHours = Math.max(hours, 1);
        saveConfig(KEY_CLEANUP_ENABLED, String.valueOf(enabled));
        saveConfig(KEY_CLEANUP_HOURS, String.valueOf(safeHours));
    }

    @Override
    @Scheduled(cron = "0 0 * * * *") // 每小时执行一次
    @Transactional
    public int cleanupExpired() {
        if (!isCleanupEnabled()) {
            return 0;
        }
        return doCleanup(cleanupHours());
    }

    @Override
    @Transactional
    public int cleanupNow() {
        return doCleanup(cleanupHours());
    }

    /** 按保留时长删除过期日志并记录日志，返回删除条数 */
    private int doCleanup(int hours) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(hours);
        long before = operationLogRepository.countByCreatedAtBefore(cutoff);
        if (before == 0) {
            return 0;
        }
        long deleted = operationLogRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("[Cleanup] 已清理 {} 条超过 {} 小时的操作日志", deleted, hours);
        }
        return (int) deleted;
    }

    private boolean isCleanupEnabled() {
        return siteConfigRepository.findByConfigKey(KEY_CLEANUP_ENABLED)
                .map(c -> "true".equalsIgnoreCase(c.getConfigValue()))
                .orElse(true);
    }

    private int cleanupHours() {
        return siteConfigRepository.findByConfigKey(KEY_CLEANUP_HOURS)
                .map(SiteConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .map(v -> {
                    try {
                        int h = Integer.parseInt(v.trim());
                        return h > 0 ? h : DEFAULT_CLEANUP_HOURS;
                    } catch (NumberFormatException e) {
                        return DEFAULT_CLEANUP_HOURS;
                    }
                })
                .orElse(DEFAULT_CLEANUP_HOURS);
    }

    private void saveConfig(String key, String value) {
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
