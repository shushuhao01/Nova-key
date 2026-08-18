package com.orionkey.controller;

import com.orionkey.common.ApiResponse;
import com.orionkey.context.RequestContext;
import com.orionkey.service.DistributionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/distribution")
@RequiredArgsConstructor
public class AdminDistributionController {

    private final DistributionService distributionService;

    // ── 概览统计（支持快捷日期区间与自定义日期，range: today/yesterday/thisMonth/lastMonth/thisYear/all/custom） ──
    @GetMapping("/overview")
    public ApiResponse<?> getOverview(
            @RequestParam(required = false) String range,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return ApiResponse.success(distributionService.adminGetOverviewStats(range, from, to));
    }

    // ── 近期分销订单明细（推广链接成交订单） ──
    @GetMapping("/recent-orders")
    public ApiResponse<?> recentOrders(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(distributionService.adminRecentDistributionOrders(from, to, limit));
    }

    // ── 规则配置 ──
    @GetMapping("/rules")
    public ApiResponse<?> getRules() {
        return ApiResponse.success(distributionService.getRules());
    }

    @PutMapping("/rules")
    public ApiResponse<?> updateRules(@RequestBody Map<String, Object> request) {
        distributionService.updateRules(request);
        return ApiResponse.success();
    }

    // ── 阶梯佣金 ──
    @GetMapping("/tiers")
    public ApiResponse<?> getTiers() {
        return ApiResponse.success(distributionService.listCommissionTiers());
    }

    @PutMapping("/tiers")
    public ApiResponse<?> updateTiers(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> tiers = (java.util.List<Map<String, Object>>) request.get("tiers");
        distributionService.updateCommissionTiers(tiers);
        return ApiResponse.success();
    }

    // ── 分销员管理 ──
    @GetMapping("/distributors")
    public ApiResponse<?> listDistributors(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(distributionService.adminListDistributors(status, keyword, from, to, page, pageSize));
    }

    @GetMapping("/distributors/{id}")
    public ApiResponse<?> getDistributor(@PathVariable UUID id) {
        return ApiResponse.success(distributionService.adminGetDistributor(id));
    }

    @PutMapping("/distributors/{id}/status")
    public ApiResponse<?> updateDistributorStatus(@PathVariable UUID id, @RequestBody Map<String, Object> request) {
        String status = (String) request.get("status");
        String reason = request.get("reason") != null ? request.get("reason").toString() : "";
        distributionService.adminUpdateDistributorStatus(id, status, reason);
        return ApiResponse.success();
    }

    @PutMapping("/distributors/{id}/rate")
    public ApiResponse<?> updateDistributorRate(@PathVariable UUID id, @RequestBody Map<String, Object> request) {
        BigDecimal customRate = request.get("custom_rate") != null
                ? new BigDecimal(request.get("custom_rate").toString()) : null;
        BigDecimal subRate = request.get("sub_rate") != null
                ? new BigDecimal(request.get("sub_rate").toString()) : null;
        distributionService.adminUpdateDistributorRate(id, customRate, subRate);
        return ApiResponse.success();
    }

    // ── 商品佣金配置 ──
    @GetMapping("/products")
    public ApiResponse<?> listProductCommissions(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(distributionService.adminListProductCommissions(page, pageSize, keyword));
    }

    // ── 商品佣金概览统计（点击/下单/转化/佣金 + 今日 + 环比，支持快捷日期筛选） ──
    @GetMapping("/products/stats")
    public ApiResponse<?> productStats(
            @RequestParam(required = false) String range,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return ApiResponse.success(distributionService.adminProductStats(range, from, to));
    }

    // ── 商品推广员排行（推广该商品的每个分销员明细） ──
    @GetMapping("/products/{productId}/promoters")
    public ApiResponse<?> productPromoters(@PathVariable UUID productId,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return ApiResponse.success(distributionService.adminProductPromoters(productId, page, pageSize));
    }

    @PutMapping("/products/{productId}")
    public ApiResponse<?> updateProductCommission(@PathVariable UUID productId, @RequestBody Map<String, Object> request) {
        BigDecimal customRate = request.get("custom_rate") != null
                ? new BigDecimal(request.get("custom_rate").toString()) : null;
        boolean excluded = Boolean.parseBoolean(request.get("excluded") != null ? request.get("excluded").toString() : "false");
        distributionService.adminUpdateProductCommission(productId, customRate, excluded);
        return ApiResponse.success();
    }

    // ── 佣金记录 ──
    @GetMapping("/commissions")
    public ApiResponse<?> listCommissions(
            @RequestParam(value = "distributor_id", required = false) UUID distributorId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(distributionService.adminListCommissions(distributorId, status, from, to, page, pageSize));
    }

    // ── 佣金记录汇总卡片（区间内按佣金创建时间动态统计，from/to 为空则全量） ──
    @GetMapping("/commissions/stats")
    public ApiResponse<?> commissionStats(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return ApiResponse.success(distributionService.adminCommissionStats(from, to));
    }

    // ── 提现管理 ──
    @GetMapping("/withdrawals")
    public ApiResponse<?> listWithdrawals(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(distributionService.adminListWithdrawals(status, from, to, page, pageSize));
    }

    // ── 提现单关联的佣金明细（查看该笔提现包含哪些订单） ──
    @GetMapping("/withdrawals/{id}/items")
    public ApiResponse<?> withdrawalItems(@PathVariable UUID id) {
        return ApiResponse.success(distributionService.adminGetWithdrawalItems(id));
    }

    // ── 提现管理汇总卡片（总销售额/总佣金/待结算/已结算，按日期区间动态统计） ──
    @GetMapping("/withdrawals/stats")
    public ApiResponse<?> withdrawalStats(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return ApiResponse.success(distributionService.adminWithdrawalStats(from, to));
    }

    @PutMapping("/withdrawals/{id}/approve")
    public ApiResponse<?> approveWithdrawal(@PathVariable UUID id) {
        distributionService.adminApproveWithdrawal(id);
        return ApiResponse.success();
    }

    @PutMapping("/withdrawals/{id}/reject")
    public ApiResponse<?> rejectWithdrawal(@PathVariable UUID id, @RequestBody Map<String, Object> request) {
        String reason = request.get("reason") != null ? request.get("reason").toString() : "";
        distributionService.adminRejectWithdrawal(id, reason);
        return ApiResponse.success();
    }

    @PutMapping("/withdrawals/{id}/settle")
    public ApiResponse<?> manualSettleWithdrawal(@PathVariable UUID id, @RequestBody Map<String, Object> request) {
        BigDecimal actualAmount = null;
        if (request.get("actual_amount") != null && !request.get("actual_amount").toString().isBlank()) {
            actualAmount = new BigDecimal(request.get("actual_amount").toString());
        }
        distributionService.adminManualSettle(id, actualAmount);
        return ApiResponse.success();
    }

    /** 查询提现单转账状态（含主动查询微信商家转账终态，回调丢失时兜底补账） */
    @GetMapping("/withdrawals/{id}/transfer-status")
    public ApiResponse<?> withdrawalTransferStatus(@PathVariable UUID id) {
        return ApiResponse.success(distributionService.adminGetWithdrawalTransferStatus(id));
    }

    /** 重新发起微信商家转账（仅已通过/转账失败状态可重试） */
    @PostMapping("/withdrawals/{id}/retry-transfer")
    public ApiResponse<?> retryWithdrawalTransfer(@PathVariable UUID id) {
        return ApiResponse.success(distributionService.adminRetryWithdrawalTransfer(id));
    }

    /** 手动结算待结算佣金：把超过结算延迟期的 PENDING 佣金转为 SETTLED 并入可提现余额（等效定时任务立即执行一次） */
    @PostMapping("/commissions/settle")
    public ApiResponse<?> settleCommissions() {
        distributionService.settlePendingCommissions();
        return ApiResponse.success();
    }
}
