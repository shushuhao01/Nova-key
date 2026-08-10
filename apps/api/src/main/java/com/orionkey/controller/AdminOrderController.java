package com.orionkey.controller;

import com.orionkey.annotation.LogOperation;
import com.orionkey.common.ApiResponse;
import com.orionkey.service.AdminOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    @GetMapping
    public ApiResponse<?> listOrders(
            @RequestParam(required = false) String status,
            @RequestParam(value = "order_type", required = false) String orderType,
            @RequestParam(value = "payment_method", required = false) String paymentMethod,
            @RequestParam(value = "is_risk_flagged", required = false) Boolean isRiskFlagged,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(adminOrderService.listOrders(status, orderType, paymentMethod,
                isRiskFlagged, keyword, page, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> getOrderDetail(@PathVariable UUID id) {
        return ApiResponse.success(adminOrderService.getOrderDetail(id));
    }

    @LogOperation(action = "order.mark_paid", targetType = "ORDER", targetId = "#id", detail = "'手动标记已付'")
    @PostMapping("/{id}/mark-paid")
    public ApiResponse<Void> markPaid(@PathVariable UUID id) {
        adminOrderService.markPaid(id);
        return ApiResponse.success();
    }

    /**
     * 订单退款：微信支付原路退回，支持全额/部分金额退款。
     *
     * @param body { amount: 退款金额（元）, reason: 退款原因 }
     */
    @LogOperation(action = "order.refund", targetType = "ORDER", targetId = "#id",
            detail = "'退款 ¥' + #body?.get('amount') + '（' + #body?.get('reason') + '）'")
    @PostMapping("/{id}/refund")
    public ApiResponse<?> refund(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        BigDecimal amount = body.get("amount") != null
                ? new BigDecimal(body.get("amount").toString())
                : null;
        String reason = body.get("reason") != null ? body.get("reason").toString() : null;
        return ApiResponse.success(adminOrderService.refund(id, amount, reason));
    }
}
