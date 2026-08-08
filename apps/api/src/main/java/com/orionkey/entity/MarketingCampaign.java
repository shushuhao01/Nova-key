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
 * 营销活动（自定义营销邮件 + 优惠券推广）。
 * 一个活动可携带一个优惠券（核销码），用户点击邮件中的领取链接后生成 UserCoupon 领取记录，
 * 下单时输入核销码即可抵扣支付金额。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "marketing_campaigns")
public class MarketingCampaign extends BaseEntity {

    /** 活动标题 */
    @Column(nullable = false)
    private String title;

    /** 邮件主题 */
    private String subject;

    /** 邮件正文（支持 HTML，保存自管理后台排版器） */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 受众类型：ALL_USERS 全部注册用户 / USER_IDS 指定用户 / EMAILS 指定邮箱 */
    @Column(nullable = false)
    private String audienceType = "ALL_USERS";

    /** 受众明细 JSON：USER_IDS → [uuid...]，EMAILS → ["a@b.com",...] */
    @Column(columnDefinition = "TEXT")
    private String targetJson;

    /** 状态：DRAFT 草稿 / SENT 已发送 / SCHEDULED 定时中 */
    @Column(nullable = false)
    private String status = "DRAFT";

    /** 已发送数量 */
    private int sentCount = 0;

    /** 发送失败数量（尝试发送但失败） */
    private int failedCount = 0;

    /** 记录类型：COUPON 纯优惠券 / EMAIL 营销邮件（旧数据 null 视为 EMAIL） */
    private String recordType = "EMAIL";

    /** 营销邮件关联的优惠券记录 ID（recordType=EMAIL 时可选，发送时取该优惠券的领取链接/核销码） */
    private UUID couponRefId;

    /** 定时发送时间（recordType=EMAIL：sendAt 晚于当前时间时保存为 SCHEDULED，到点自动发送） */
    private LocalDateTime sendAt;

    /** 作废标记：1=已作废（优惠券不可再领取/使用） */
    private int isCanceled = 0;

    // ── 优惠券配置（活动可携带一个核销码） ──

    /** 优惠券类型：AMOUNT 立减金额 / PERCENT 折扣比例（减免百分比） */
    private String couponType;

    /** 立减金额 或 折扣比例值（PERCENT 时为减免百分比，如 20 表示 8 折） */
    @Column(precision = 10, scale = 2)
    private BigDecimal couponValue;

    /** 满减门槛（可选，订单金额 ≥ 此值才可用） */
    @Column(precision = 10, scale = 2)
    private BigDecimal couponMinAmount;

    /** 优惠券核销码（用户领取后凭此码下单抵扣） */
    private String couponCode;

    /** 可领取总数量 */
    private int couponQuantity = 0;

    /** 优惠券生效开始时间 */
    private LocalDateTime couponValidFrom;

    /** 优惠券生效结束时间 */
    private LocalDateTime couponValidTo;

    /** 优惠券适用范围：ALL 全部商品通用 / SPECIFIC 仅指定商品可用 */
    private String couponScope = "ALL";

    /** 指定商品 ID 列表 JSON：["uuid1","uuid2"]（couponScope=SPECIFIC 时生效） */
    @Column(columnDefinition = "TEXT")
    private String couponProductIds;
}
