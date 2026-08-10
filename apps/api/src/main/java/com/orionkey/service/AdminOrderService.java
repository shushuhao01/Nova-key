package com.orionkey.service;

import com.orionkey.common.PageResult;

import java.math.BigDecimal;
import java.util.UUID;

public interface AdminOrderService {

    PageResult<?> listOrders(String status, String orderType, String paymentMethod,
                             Boolean isRiskFlagged, String keyword, int page, int pageSize);

    Object getOrderDetail(UUID id);

    void markPaid(UUID id);

    /**
     * 订单退款（微信支付原路退回）。
     *
     * @param id     订单 ID
     * @param amount 退款金额（元），全额或部分，不能超过订单实付金额
     * @param reason 退款原因（必填）
     * @return 退款结果摘要（含退款金额 / 退款单号 / 订单新状态）
     */
    Map<String, Object> refund(UUID id, BigDecimal amount, String reason);
}
