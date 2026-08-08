package com.orionkey.controller;

import com.orionkey.annotation.LogOperation;
import com.orionkey.common.ApiResponse;
import com.orionkey.entity.SystemMessage;
import com.orionkey.service.impl.NotificationServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 消息通知管理：通知渠道（钉钉/企业微信/邮箱）、预设模板启用勾选、
 * 测试发送、系统消息（后台铃铛：列表/已读/清空）。
 */
@RestController
@RequestMapping("/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final NotificationServiceImpl notificationService;

    // ═══════════ 模板 ═══════════

    @GetMapping("/templates")
    public ApiResponse<?> listTemplates() {
        return ApiResponse.success(notificationService.listTemplates());
    }

    @LogOperation(action = "notify.update", targetType = "NOTIFY_TEMPLATE", targetId = "#id", detail = "'更新消息通知模板'")
    @PutMapping("/templates/{id}")
    public ApiResponse<Void> updateTemplate(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        notificationService.updateTemplate(id, body);
        return ApiResponse.success();
    }

    // ═══════════ 渠道 ═══════════

    @GetMapping("/channels")
    public ApiResponse<?> listChannels() {
        return ApiResponse.success(notificationService.listChannels());
    }

    @LogOperation(action = "notify.save", targetType = "NOTIFY_CHANNEL", detail = "#channelType")
    @PutMapping("/channels/{channelType}")
    public ApiResponse<Void> saveChannel(@PathVariable String channelType, @RequestBody Map<String, Object> body) {
        notificationService.saveChannel(channelType, body);
        return ApiResponse.success();
    }

    // ═══════════ 测试发送 ═══════════

    /** 测试发送指定模板：返回逐渠道检测结果 {passed, items:[{name,status,message}]} */
    @LogOperation(action = "notify.test", targetType = "NOTIFY_TEMPLATE", detail = "#body.get('template_code')")
    @PostMapping("/test")
    public ApiResponse<?> testSend(@RequestBody Map<String, Object> body) {
        String code = String.valueOf(body.get("template_code"));
        return ApiResponse.success(notificationService.testSend(code));
    }

    // ═══════════ 系统消息（铃铛） ═══════════

    @GetMapping("/messages")
    public ApiResponse<?> listMessages(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int pageSize,
                                       @RequestParam(required = false) Boolean unread) {
        Page<SystemMessage> result = notificationService.listMessages(page, pageSize, unread);
        return ApiResponse.success(Map.of(
                "list", result.getContent(),
                "pagination", Map.of("page", page, "page_size", pageSize, "total", result.getTotalElements())
        ));
    }

    @GetMapping("/messages/unread-count")
    public ApiResponse<?> unreadCount() {
        return ApiResponse.success(Map.of("count", notificationService.unreadCount()));
    }

    @PostMapping("/messages/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable UUID id) {
        notificationService.markRead(id);
        return ApiResponse.success();
    }

    @PostMapping("/messages/read-all")
    public ApiResponse<?> markAllRead() {
        return ApiResponse.success(Map.of("updated", notificationService.markAllRead()));
    }

    @LogOperation(action = "notify.clear", targetType = "SYSTEM_MESSAGE", detail = "'清空系统消息'")
    @PostMapping("/messages/clear")
    public ApiResponse<Void> clearMessages() {
        notificationService.clearAll();
        return ApiResponse.success();
    }
}
