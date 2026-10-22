package com.orionkey.controller;

import com.orionkey.annotation.LogOperation;
import com.orionkey.common.ApiResponse;
import com.orionkey.service.AdminOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
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
            @RequestParam(value = "start_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "end_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(adminOrderService.listOrders(status, orderType, paymentMethod,
                isRiskFlagged, keyword, startDate, endDate, page, pageSize));
    }

    /** 订单统计卡片：与列表同一套筛选条件（日期按创建时间） */
    @GetMapping("/stats")
    public ApiResponse<?> orderStats(
            @RequestParam(required = false) String status,
            @RequestParam(value = "order_type", required = false) String orderType,
            @RequestParam(value = "payment_method", required = false) String paymentMethod,
            @RequestParam(value = "is_risk_flagged", required = false) Boolean isRiskFlagged,
            @RequestParam(required = false) String keyword,
            @RequestParam(value = "start_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "end_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(adminOrderService.getOrderStats(status, orderType, paymentMethod,
                isRiskFlagged, keyword, startDate, endDate));
    }

    /** 业绩结算报表：汇总卡片 + 趋势 + 支付方式/订单类型分布 + 分销佣金 */
    @GetMapping("/settlement")
    public ApiResponse<?> settlementReport(
            @RequestParam(value = "start_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "end_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(adminOrderService.getSettlementReport(startDate, endDate));
    }

    /** 推广员排行（业绩结算「查看更多」）：分页返回完整排行数据 */
    @GetMapping("/settlement/distributors")
    public ApiResponse<?> settlementDistributors(
            @RequestParam(value = "start_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "end_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return ApiResponse.success(adminOrderService.listSettlementDistributors(startDate, endDate, page, pageSize));
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
