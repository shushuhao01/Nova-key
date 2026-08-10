package com.orionkey.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DistributionService {

    // ── 分销员 ──
    Map<String, Object> applyDistributor(UUID userId, String inviteCode);
    Map<String, Object> getDistributorProfile(UUID userId);
    Map<String, Object> getDistributorStats(UUID userId);

    // ── 管理后台：分销员管理 ──
    Map<String, Object> adminListDistributors(String status, String keyword, int page, int pageSize);
    Map<String, Object> adminGetDistributor(UUID id);
    void adminUpdateDistributorStatus(UUID id, String status, String reason);
    void adminUpdateDistributorRate(UUID id, BigDecimal customRate, BigDecimal subRate);

    // ── 管理后台：规则配置 ──
    Map<String, Object> getRules();
    void updateRules(Map<String, Object> request);

    // ── 管理后台：商品佣金 ──
    Map<String, Object> adminListProductCommissions(int page, int pageSize);
    void adminUpdateProductCommission(UUID productId, BigDecimal customRate, boolean excluded);

    // ── 管理后台：佣金记录 ──
    Map<String, Object> adminListCommissions(UUID distributorId, String status, int page, int pageSize);
    Map<String, Object> adminCommissionStats();

    // ── 管理后台：提现管理 ──
    Map<String, Object> adminListWithdrawals(String status, int page, int pageSize);
    void adminApproveWithdrawal(UUID id);
    void adminRejectWithdrawal(UUID id, String reason);
    /** 手动结算：管理员确认已线下支付，从冻结余额扣减，状态改为 SUCCESS */
    void adminManualSettle(UUID id, BigDecimal actualAmount);

    // ── 管理后台：统计 ──
    Map<String, Object> adminGetOverviewStats();

    // ── 前台：推广商品 ──
    Map<String, Object> listPromotionProducts(int page, int pageSize);
    Map<String, Object> listMyPromotionProducts(UUID userId, int page, int pageSize);
    Map<String, Object> generatePromotionLink(UUID userId, UUID productId);
    Map<String, Object> generateStoreLink(UUID userId);
    Map<String, Object> listMyLinks(UUID userId, int page, int pageSize);

    // ── 前台：佣金明细 ──
    Map<String, Object> listMyCommissions(UUID userId, String status, int page, int pageSize);

    // ── 前台：提现 ──
    Map<String, Object> applyWithdrawal(UUID userId, BigDecimal amount);
    Map<String, Object> listMyWithdrawals(UUID userId, int page, int pageSize);

    // ── 前台：下级分销员 ──
    Map<String, Object> listSubordinates(UUID userId, int page, int pageSize);

    // ── 前台：客户管理 ──
    Map<String, Object> listMyCustomers(UUID userId, int page, int pageSize);

    // ── 公开：推广链接解析 ──
    Map<String, Object> resolvePromotionLink(String linkCode, String ip, String userAgent);

    // ── 佣金预估 ──
    Map<String, Object> commissionPreview(UUID userId, List<UUID> productIds);

    // ── 佣金计算（订单支付成功时调用） ──
    void onOrderPaid(UUID orderId);

    // ── 佣金结算（定时任务） ──
    void settlePendingCommissions();

    // ── 佣金取消（订单退款时调用） ──
    void cancelCommissions(UUID orderId);

    // ── 微信转账结果回调处理 ──
    void handleTransferCallback(String outBillNo, String state, String failReason);

    // ── 阶梯佣金配置 ──
    List<Map<String, Object>> listCommissionTiers();
    void updateCommissionTiers(List<Map<String, Object>> tiers);
}
