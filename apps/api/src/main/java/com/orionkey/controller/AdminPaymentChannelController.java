package com.orionkey.controller;

import com.orionkey.annotation.LogOperation;
import com.orionkey.common.ApiResponse;
import com.orionkey.service.AdminPaymentChannelService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/payment-channels")
@RequiredArgsConstructor
public class AdminPaymentChannelController {

    private final AdminPaymentChannelService adminPaymentChannelService;

    @GetMapping
    public ApiResponse<?> listChannels() {
        return ApiResponse.success(adminPaymentChannelService.listChannels());
    }

    @LogOperation(action = "payment.create", targetType = "PAYMENT_CHANNEL", detail = "'创建支付渠道'")
    @PostMapping
    public ApiResponse<Void> createChannel(@RequestBody Map<String, Object> request) {
        adminPaymentChannelService.createChannel(request);
        return ApiResponse.success();
    }

    @LogOperation(action = "payment.update", targetType = "PAYMENT_CHANNEL", targetId = "#id", detail = "'修改支付渠道'")
    @PutMapping("/{id}")
    public ApiResponse<Void> updateChannel(@PathVariable UUID id, @RequestBody Map<String, Object> request) {
        adminPaymentChannelService.updateChannel(id, request);
        return ApiResponse.success();
    }

    @LogOperation(action = "payment.delete", targetType = "PAYMENT_CHANNEL", targetId = "#id", detail = "'删除支付渠道'")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteChannel(@PathVariable UUID id) {
        adminPaymentChannelService.deleteChannel(id);
        return ApiResponse.success();
    }

    /** 测试支付渠道配置与支付平台的连通性，返回 { success, message: 详细原因 } */
    @LogOperation(action = "payment.test", targetType = "PAYMENT_CHANNEL", targetId = "#id", detail = "'测试支付渠道连接'")
    @PostMapping("/{id}/test")
    public ApiResponse<?> testChannel(@PathVariable UUID id) {
        return ApiResponse.success(adminPaymentChannelService.testChannel(id));
    }
}
