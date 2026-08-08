package com.orionkey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户优惠券领取记录（营销活动核销码）。
 * 用户点击领取后生成一条记录（绑定 userId 或 email），下单时凭 code 核销抵扣。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_coupons")
public class UserCoupon extends BaseEntity {

    /** 所属营销活动 */
    private UUID campaignId;

    /** 优惠券核销码 */
    @Column(nullable = false)
    private String code;

    /** 领取人用户 ID（匿名领取时为 null） */
    private UUID userId;

    /** 领取人邮箱（登录用户领取时冗余存储，匿名领取必填） */
    private String email;

    /** 优惠券类型：AMOUNT / PERCENT */
    private String type;

    /** 立减金额 或 折扣比例值 */
    @Column(name = "coupon_value", precision = 10, scale = 2)
    private BigDecimal value;

    /** 状态：CLAIMED 已领取未使用 / USED 已使用 / EXPIRED 已过期 */
    @Column(nullable = false)
    private String status = "CLAIMED";

    private LocalDateTime claimedAt;

    private LocalDateTime usedAt;

    /** 使用该券的订单 */
    private UUID orderId;

    /** 生效开始时间 */
    private LocalDateTime validFrom;

    /** 生效结束时间 */
    private LocalDateTime validTo;

    /** 适用范围：ALL 全部商品通用 / SPECIFIC 仅指定商品可用（领取时快照自活动） */
    private String scope = "ALL";

    /** 指定商品 ID 列表 JSON（scope=SPECIFIC 时生效，领取时快照自活动） */
    @Column(columnDefinition = "TEXT")
    private String productIds;
}
