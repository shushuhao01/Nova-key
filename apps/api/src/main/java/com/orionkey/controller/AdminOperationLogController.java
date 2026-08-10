package com.orionkey.controller;

import com.orionkey.annotation.LogOperation;
import com.orionkey.common.ApiResponse;
import com.orionkey.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/operation-logs")
@RequiredArgsConstructor
public class AdminOperationLogController {

    private final OperationLogService operationLogService;

    @GetMapping
    public ApiResponse<?> listLogs(
            @RequestParam(value = "user_id", required = false) UUID userId,
            @RequestParam(required = false) String action,
            @RequestParam(value = "target_type", required = false) String targetType,
            @RequestParam(value = "start_date", required = false) String startDate,
            @RequestParam(value = "end_date", required = false) String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(operationLogService.listLogs(userId, action, targetType, startDate, endDate, page, pageSize));
    }

    // ═══════════ 定时清理配置 ═══════════

    /** 获取定时清理配置（默认启用、24 小时后清理） */
    @GetMapping("/cleanup-config")
    public ApiResponse<?> getCleanupConfig() {
        return ApiResponse.success(operationLogService.getCleanupConfig());
    }

    /** 保存定时清理配置（启用开关 + 保留时长小时数） */
    @LogOperation(action = "log.cleanup_config", targetType = "OPERATION_LOG", detail = "'保存操作日志清理配置'")
    @PutMapping("/cleanup-config")
    public ApiResponse<Void> saveCleanupConfig(@RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        int hours = body.get("hours") instanceof Number n ? n.intValue() : 24;
        operationLogService.saveCleanupConfig(enabled, hours);
        return ApiResponse.success();
    }

    /** 立即清理过期日志（按当前保留时长） */
    @LogOperation(action = "log.cleanup", targetType = "OPERATION_LOG", detail = "'立即清理操作日志'")
    @PostMapping("/cleanup")
    public ApiResponse<?> cleanupNow() {
        return ApiResponse.success(Map.of("deleted", operationLogService.cleanupNow()));
    }
}
