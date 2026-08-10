package com.orionkey.controller;

import com.orionkey.common.ApiResponse;
import com.orionkey.context.RequestContext;
import com.orionkey.service.UserMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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
    public ApiResponse<?> recent() {
        return ApiResponse.success(messageService.recentMessages(RequestContext.getUserId(), 5));
    }

    @GetMapping
    public ApiResponse<?> list(
            @RequestParam(required = false) String category,
            @RequestParam(value = "unreadOnly", defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return ApiResponse.success(messageService.listMessages(RequestContext.getUserId(), category, unreadOnly, page, pageSize));
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
}
