package com.orionkey.service;

import com.orionkey.common.PageResult;

import java.util.Map;
import java.util.UUID;

public interface OperationLogService {

    PageResult<?> listLogs(UUID userId, String action, String targetType,
                            String startDate, String endDate, int page, int pageSize);

    void log(UUID userId, String username, String action, String targetType, String targetId, String detail, String ip);

    /** 获取操作日志定时清理配置（默认启用、24 小时后清理） */
    Map<String, Object> getCleanupConfig();

    /** 保存操作日志定时清理配置 */
    void saveCleanupConfig(boolean enabled, int hours);

    /** 定时清理：按配置的保留时长删除过期日志，返回删除条数 */
    int cleanupExpired();

    /** 立即清理：按配置的保留时长删除过期日志（不受启用开关限制），返回删除条数 */
    int cleanupNow();
}
