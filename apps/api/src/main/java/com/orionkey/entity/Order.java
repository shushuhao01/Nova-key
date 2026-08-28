package com.orionkey.entity;

import com.orionkey.constant.OrderStatus;
import com.orionkey.constant.OrderType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    private UUID userId;

    private String email;

    @Column(precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(precision = 10, scale = 2)
    private BigDecimal actualAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderType orderType;

    private String paymentMethod;

    private int pointsDeducted = 0;

    @Column(precision = 10, scale = 2)
    private BigDecimal pointsDiscount = BigDecimal.ZERO;

    /** 使用的优惠券核销码（选填） */
    private String couponCode;

    /** 优惠券抵扣金额 */
    @Column(precision = 10, scale = 2)
    private BigDecimal couponDiscount = BigDecimal.ZERO;

    private LocalDateTime expiresAt;

    private LocalDateTime paidAt;

    private LocalDateTime deliveredAt;

    /** 订单完成时间（已发货订单 24h 后自动置为已完成时设置） */
    private LocalDateTime completedAt;

    /** 已退款金额（0 = 未退款） */
    @Column(precision = 10, scale = 2)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    /** 退款原因 */
    @Column(columnDefinition = "TEXT")
    private String refundReason;

    /** 商户退款单号（out_refund_no） */
    private String outRefundNo;

    /** 微信退款单号（refund_id） */
    private String wxRefundId;

    /** 退款时间 */
    private LocalDateTime refundedAt;

    @Column(unique = true)
    private String idempotencyKey;

    @Column(name = "is_risk_flagged")
    private boolean riskFlagged = false;

    private String clientIp;

    /** 下单设备/来源：PC浏览器(含具体浏览器)、手机浏览器、微信等，由 User-Agent 解析 */
    private String device;

    private String sessionToken;

    @Column(columnDefinition = "TEXT")
    private String paymentUrl;

    @Column(columnDefinition = "TEXT")
    private String qrcodeUrl;

    /** 微信内 JSAPI 支付拉起参数 JSON（appId/timeStamp/nonceStr/package/signType/paySign），仅微信内调用 */
    @Column(columnDefinition = "TEXT")
    private String jsapiPayParams;

    private String epayTradeNo;

    // ── USDT 支付字段 ──

    /** 收款钱包地址 */
    private String usdtWalletAddress;

    /** 精确加密货币金额（3 位小数） */
    private String usdtCryptoAmount;

    /** BEpusdt 交易 ID */
    private String usdtTradeId;

    /** 链标识，如 usdt_trc20 / usdt_bep20 */
    private String usdtChain;

    /** 链上交易哈希（支付成功后填充） */
    @Column(unique = true)
    private String usdtTxId;

    // ── 分销推广字段 ──

    /** 推广员 ID（来自推广链接，用于佣金计算） */
    private UUID referralDistributorId;

    /** 推广链接 ID */
    private UUID promotionLinkId;
}
