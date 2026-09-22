package com.orionkey.service;

import com.orionkey.common.PageResult;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public interface AdminOrderService {

    PageResult<?> listOrders(String status, String orderType, String paymentMethod,
                             Boolean isRiskFlagged, String keyword, int page, int pageSize);

    Object getOrderDetail(UUID id);

    void markPaid(UUID id);

    /**
     * 订单退款（微信支付原路退回）。
     * <p>
     * 微信同步确认退款成功（status=SUCCESS）时直接落终态；仅受理（status=PROCESSING）时
     * 订单状态保持不变、退款状态置为 PENDING，等待退款结果通知或定时回查确认到账。
     *
     * @param id     订单 ID
     * @param amount 退款金额（元），全额或部分，不能超过订单实付金额
     * @param reason 退款原因（必填）
     * @return 退款结果摘要（含退款金额 / 退款单号 / 订单状态 / 退款状态）
     */
    Map<String, Object> refund(UUID id, BigDecimal amount, String reason);

    /**
     * 按微信确认的结果落退款终态（退款结果通知 / 主动回查调用）。
     *
     * @param outRefundNo  商户退款单号
     * @param refundStatus 微信退款状态（SUCCESS=退款成功；CLOSED/ABNORMAL=退款未成功）
     * @param refundCents  微信确认的退款金额（分）
     * @return true=已按终态处理（含重复通知的幂等返回）；false=处理失败（需微信重试）
     */
    boolean finalizeWxpayRefund(String outRefundNo, String refundStatus, Integer refundCents);

    /**
     * 兜底回查所有处于 PENDING 的退款单，确认其最终状态（定时任务调用）。
     *
     * @return 本次确认到终态的退款单数量
     */
    int reconcilePendingRefunds();
}
