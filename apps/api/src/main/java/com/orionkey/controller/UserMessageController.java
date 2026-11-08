package com.orionkey.controller;

import com.orionkey.common.ApiResponse;
import com.orionkey.common.PageResult;
import com.orionkey.context.RequestContext;
import com.orionkey.entity.UserMessage;
import com.orionkey.service.UserMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/user/messages")
@RequiredArgsConstructor
public class UserMessageController {

    private final UserMessageService messageService;

    @GetMapping("/unread-count")
    public ApiResponse<?> unreadCount() {
        long count = messageService.unreadCount(RequestContext.getUserId());
        return ApiResponse.success(Map.of("count", count));
    }

    @GetMapping("/recent")
    public ApiResponse<List<Map<String, Object>>> recent() {
        List<Map<String, Object>> items = messageService.recentMessages(RequestContext.getUserId(), 5)
                .stream().map(UserMessageController::toMap).toList();
        return ApiResponse.success(items);
    }

    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) String category,
            @RequestParam(value = "unreadOnly", defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        Page<UserMessage> result = messageService.listMessages(
                RequestContext.getUserId(), category, unreadOnly, page, pageSize);
        List<Map<String, Object>> items = result.getContent().stream()
                .map(UserMessageController::toMap).toList();
        return ApiResponse.success(PageResult.of(result, items));
    }

    @PutMapping("/{id}/read")
    public ApiResponse<?> markRead(@PathVariable UUID id) {
        messageService.markRead(RequestContext.getUserId(), id);
        return ApiResponse.success();
    }

    @PutMapping("/read-all")
    public ApiResponse<?> markAllRead() {
        messageService.markAllRead(RequestContext.getUserId());
        return ApiResponse.success();
    }

    @DeleteMapping
    public ApiResponse<?> clearAll() {
        messageService.clearAll(RequestContext.getUserId());
        return ApiResponse.success();
    }

    /** 显式输出前端字段（实体布尔字段 read 序列化后为 read，前端约定为 is_read） */
    private static Map<String, Object> toMap(UserMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("category", m.getCategory() != null ? m.getCategory().name() : "SYSTEM");
        map.put("title", m.getTitle());
        map.put("content", m.getContent());
        map.put("is_read", m.isRead());
        map.put("created_at", m.getCreatedAt());
        map.put("read_at", m.getReadAt());
        map.put("metadata", m.getMetadata());
        return map;
    }
}
