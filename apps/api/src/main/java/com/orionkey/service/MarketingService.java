package com.orionkey.service;

import com.orionkey.common.PageResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface MarketingService {

    // ═══════════ 优惠券管理（recordType=COUPON） ═══════════

    PageResult<?> listCoupons(String keyword, int page, int pageSize);

    Map<String, Object> getCoupon(UUID id);

    Map<String, Object> createCoupon(Map<String, Object> body);

    Map<String, Object> updateCoupon(UUID id, Map<String, Object> body);

    /** 作废优惠券（不可再领取/使用；已领取的不受影响） */
    void cancelCoupon(UUID id);

    void deleteCoupon(UUID id);

    // ═══════════ 营销邮件（recordType=EMAIL） ═══════════

    PageResult<?> listEmailCampaigns(String keyword, String status, int page, int pageSize);

    Map<String, Object> getEmailCampaign(UUID id);

    Map<String, Object> createEmailCampaign(Map<String, Object> body);

    Map<String, Object> updateEmailCampaign(UUID id, Map<String, Object> body);

    void deleteEmailCampaign(UUID id);

    /**
     * 发送营销邮件：send_at 晚于当前时间 → 定时（SCHEDULED），到点由定时任务发送；
     * 否则立即发送（SENT）。关联优惠券时校验发行数量 ≥ 收件人数（不足报"分配不足"），
     * 并按收件人逐封替换占位符（{username}/{site_url}/{claim_url}/{coupon_code}）。
     */
    Map<String, Object> sendEmailCampaign(UUID id);

    /** 营销邮件收件人分页列表（发送用户超链接弹窗用，默认 10 条/页，含送达统计） */
    Map<String, Object> campaignRecipients(UUID id, int page, int pageSize);

    /** 营销邮件受众建议：注册用户用户名列表 + 匿名订购邮箱列表（新建邮件时预填受众用） */
    Map<String, Object> audienceSuggestions();

    // ═══════════ 前台：优惠券领取 / 我的优惠券 / 核销校验 ═══════════

    /** 领取优惠券（登录用户绑定 userId，匿名需传 email）。已领取时幂等返回成功。 */
    Map<String, Object> claimCoupon(String code, UUID userId, String email);

    /** 公开查询优惠券信息（领取页展示：标题/类型/金额/有效期/剩余数量/是否作废） */
    Map<String, Object> couponInfo(String code);

    /** 个人中心优惠券列表（status=ALL/CLAIMED/USED/EXPIRED） */
    PageResult<?> myCoupons(UUID userId, String status, int page, int pageSize);

    /**
     * 校验优惠券并计算对指定金额的抵扣（下单页预览用）。
     *
     * @param productIds 订单内商品 ID 列表（用于校验"仅指定商品可用"的优惠券）
     */
    Map<String, Object> validateCoupon(String code, UUID userId, String email, BigDecimal amount, List<UUID> productIds);

    /**
     * 下单时应用优惠券：校验有效性 → 计算抵扣 → 原子核销绑定订单。
     *
     * @param productIds 订单内商品 ID 列表（用于校验"仅指定商品可用"的优惠券）
     * @return 抵扣金额（≥0）
     * @throws com.orionkey.exception.BusinessException 无效/已使用/未生效/不适用商品时抛出
     */
    BigDecimal applyCoupon(String code, UUID userId, String email, BigDecimal totalAmount, UUID orderId, List<UUID> productIds);
}
