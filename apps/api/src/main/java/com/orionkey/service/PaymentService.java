package com.orionkey.service;

import java.util.Map;
import java.util.UUID;

public interface PaymentService {

    /**
     * Create payment for an order.
     * Returns payment info: {order_id, payment_url, expires_at}
     */
    Map<String, Object> createPayment(UUID orderId, String paymentMethod, java.math.BigDecimal amount);

    /**
     * Create payment with device hint (pc/mobile/wechat/alipay).
     */
    Map<String, Object> createPayment(UUID orderId, String paymentMethod, java.math.BigDecimal amount, String device);

    /**
     * Create payment with device hint and optional wechat openid.
     * <p>
     * 当 device=wechat（微信浏览器内）且 openid 非空时，走 JSAPI 支付（直接拉起微信支付），
     * 否则回退 Native 扫码/H5。openid 仅在微信内 JSAPI 场景使用。
     */
    Map<String, Object> createPayment(UUID orderId, String paymentMethod, java.math.BigDecimal amount,
                                      String device, String openid);

    /**
     * Re-initiate payment for a PENDING order (clears cached URLs, requests new payment link).
     * @param requestUserId 当前请求用户的 ID（可为 null，表示未登录/游客）
     */
    Map<String, Object> repay(UUID orderId, String device, UUID requestUserId);

    /**
     * Re-initiate payment with optional wechat openid（微信内 JSAPI 场景）。
     */
    Map<String, Object> repay(UUID orderId, String device, String openid, UUID requestUserId);

    /**
     * 主动查询支付网关订单状态，已支付则标记为 PAID。
     * 仅在订单为 PENDING 且渠道支持主动查单（epay / native_wxpay / native_alipay）时执行。
     *
     * @return 是否发生了支付状态变更（false 表示未支付、状态不适用或查询失败）
     */
    boolean settleByActiveQuery(UUID orderId);

    /**
     * 带节流的主动查单（供轮询接口调用，避免高频查询支付网关）。内部调用 {@link #settleByActiveQuery}。
     */
    boolean maybeSettleByActiveQuery(UUID orderId);
}
