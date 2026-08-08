package com.orionkey.service;

import com.orionkey.common.PageResult;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public interface MarketingService {

    // ── 管理后台：营销活动 CRUD ──

    PageResult<?> listCampaigns(String keyword, String status, int page, int pageSize);

    Map<String, Object> getCampaign(UUID id);

    Map<String, Object> createCampaign(Map<String, Object> body);

    Map<String, Object> updateCampaign(UUID id, Map<String, Object> body);

    void deleteCampaign(UUID id);

    /** 发送营销邮件（受众 = 全部/指定用户/指定邮箱），发送完成标记 SENT */
    Map<String, Object> sendCampaign(UUID id);

    // ── 前台：优惠券领取 / 校验 ──

    /** 领取优惠券（登录用户绑定 userId，匿名需传 email） */
    Map<String, Object> claimCoupon(String code, UUID userId, String email);

    /** 校验优惠券并计算对指定金额的抵扣（下单页预览用） */
    Map<String, Object> validateCoupon(String code, UUID userId, String email, BigDecimal amount);

    /**
     * 下单时应用优惠券：校验有效性 → 计算抵扣 → 原子核销绑定订单。
     *
     * @return 抵扣金额（≥0）
     * @throws com.orionkey.exception.BusinessException 无效/已使用/未生效时抛出
     */
    BigDecimal applyCoupon(String code, UUID userId, String email, BigDecimal totalAmount, UUID orderId);
}
