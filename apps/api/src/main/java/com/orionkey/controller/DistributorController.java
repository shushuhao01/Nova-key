package com.orionkey.controller;

import com.orionkey.common.ApiResponse;
import com.orionkey.context.RequestContext;
import com.orionkey.service.DistributionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/distributor")
@RequiredArgsConstructor
public class DistributorController {

    private final DistributionService distributionService;

    // ── 分销员申请 ──
    @PostMapping("/apply")
    public ApiResponse<?> apply(@RequestBody(required = false) Map<String, Object> request) {
        String inviteCode = request != null ? (String) request.get("invite_code") : null;
        return ApiResponse.success(distributionService.applyDistributor(RequestContext.getUserId(), inviteCode));
    }

    // ── 分销员信息 ──
    @GetMapping("/profile")
    public ApiResponse<?> getProfile() {
        return ApiResponse.success(distributionService.getDistributorProfile(RequestContext.getUserId()));
    }

    // ── 分销规则（前台查看，含阶梯佣金配置） ──
    @GetMapping("/rules")
    public ApiResponse<?> getRules() {
        Map<String, Object> rules = distributionService.getRules();
        rules.put("tiers", distributionService.listCommissionTiers());
        return ApiResponse.success(rules);
    }

    // ── 统计数据 ──
    @GetMapping("/stats")
    public ApiResponse<?> getStats(@RequestParam(required = false, defaultValue = "all") String range) {
        return ApiResponse.success(distributionService.getDistributorStats(RequestContext.getUserId(), range));
    }

    // ── 推广商品 ──
    @GetMapping("/products")
    public ApiResponse<?> listPromotionProducts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(distributionService.listPromotionProducts(page, pageSize));
    }

    @GetMapping("/products/mine")
    public ApiResponse<?> listMyPromotionProducts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(distributionService.listMyPromotionProducts(RequestContext.getUserId(), page, pageSize));
    }

    @PostMapping("/products/{productId}/link")
    public ApiResponse<?> generatePromotionLink(@PathVariable UUID productId) {
        return ApiResponse.success(distributionService.generatePromotionLink(RequestContext.getUserId(), productId));
    }

    // ── 全店推广 ──
    @PostMapping("/store/link")
    public ApiResponse<?> generateStoreLink() {
        return ApiResponse.success(distributionService.generateStoreLink(RequestContext.getUserId()));
    }

    // 全店推广累计统计（点击/支付/转化率/佣金，与商品分享数据独立）
    @GetMapping("/store/stats")
    public ApiResponse<?> getStoreStats() {
        return ApiResponse.success(distributionService.getStoreStats(RequestContext.getUserId()));
    }

    // ── 客户邀请码绑定（个人中心补填） ──
    @PostMapping("/customer/bind")
    public ApiResponse<?> bindCustomer(@RequestBody Map<String, Object> request) {
        String inviteCode = request != null ? (String) request.get("invite_code") : null;
        return ApiResponse.success(distributionService.bindCustomerByInviteCode(RequestContext.getUserId(), inviteCode));
    }

    @GetMapping("/customer/binding")
    public ApiResponse<?> getCustomerBinding() {
        return ApiResponse.success(distributionService.getCustomerBinding(RequestContext.getUserId()));
    }

    // ── 推广海报 ──
    @PostMapping("/products/{productId}/poster")
    public ApiResponse<?> generateProductPoster(@PathVariable UUID productId) {
        return ApiResponse.success(distributionService.generateProductPoster(RequestContext.getUserId(), productId));
    }

    @PostMapping("/store/poster")
    public ApiResponse<?> generateStorePoster() {
        return ApiResponse.success(distributionService.generateStorePoster(RequestContext.getUserId()));
    }

    // ── 微信绑定（提现收款） ──
    @GetMapping("/wechat/bind-url")
    public ApiResponse<?> getWechatBindUrl() {
        return ApiResponse.success(distributionService.getWechatBindUrl(RequestContext.getUserId()));
    }

    @GetMapping("/wechat/callback")
    public ApiResponse<?> wechatCallback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state) {
        return ApiResponse.success(distributionService.wechatCallback(code, state));
    }

    @PostMapping("/wechat/unbind")
    public ApiResponse<?> unbindWechat() {
        distributionService.unbindWechat(RequestContext.getUserId());
        return ApiResponse.success(null);
    }

    @GetMapping("/wechat/status")
    public ApiResponse<?> wechatStatus() {
        return ApiResponse.success(distributionService.wechatStatus(RequestContext.getUserId()));
    }

    // ── 我的推广链接 ──
    @GetMapping("/links")
    public ApiResponse<?> listMyLinks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(distributionService.listMyLinks(RequestContext.getUserId(), page, pageSize));
    }

    // ── 最近推广成交订单（含下级推广订单） ──
    @GetMapping("/orders")
    public ApiResponse<?> listMyPromotionOrders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return ApiResponse.success(distributionService.listMyPromotionOrders(RequestContext.getUserId(), page, pageSize));
    }

    // ── 佣金明细 ──
    @GetMapping("/commissions")
    public ApiResponse<?> listMyCommissions(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(distributionService.listMyCommissions(RequestContext.getUserId(), status, page, pageSize));
    }

    /** 佣金明细导出 Excel（全部数据，status 可选过滤） */
    @GetMapping("/commissions/export")
    public ResponseEntity<byte[]> exportCommissions(@RequestParam(required = false) String status) {
        byte[] bytes = distributionService.exportMyCommissions(RequestContext.getUserId(), status);
        String filename = URLEncoder.encode("佣金明细_" + LocalDate.now(), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename + ".xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    // ── 提现 ──
    /** 可提现订单列表（已结算/结算拒绝的佣金记录按订单分组，供提现弹窗勾选） */
    @GetMapping("/withdrawals/withdrawable-orders")
    public ApiResponse<?> withdrawableOrders() {
        return ApiResponse.success(distributionService.getWithdrawableOrders(RequestContext.getUserId()));
    }

    /** 按勾选的佣金记录申请提现（订单级提现，金额自动汇总） */
    @PostMapping("/withdrawals")
    public ApiResponse<?> applyWithdrawal(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> ids = request.get("commission_record_ids") != null
                ? (List<String>) request.get("commission_record_ids") : List.of();
        List<UUID> recordIds = ids.stream().map(UUID::fromString).toList();
        return ApiResponse.success(distributionService.applyWithdrawal(RequestContext.getUserId(), recordIds));
    }

    @GetMapping("/withdrawals")
    public ApiResponse<?> listMyWithdrawals(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(distributionService.listMyWithdrawals(RequestContext.getUserId(), page, pageSize));
    }

    // ── 下级分销员 ──
    @GetMapping("/subordinates")
    public ApiResponse<?> listSubordinates(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(distributionService.listSubordinates(RequestContext.getUserId(), page, pageSize));
    }

    // ── 客户管理 ──
    @GetMapping("/customers")
    public ApiResponse<?> listMyCustomers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(distributionService.listMyCustomers(RequestContext.getUserId(), page, pageSize));
    }
}
