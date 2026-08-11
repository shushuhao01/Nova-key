package com.orionkey.service.impl;

import com.orionkey.common.PageResult;
import com.orionkey.constant.ErrorCode;
import com.orionkey.constant.OrderStatus;
import com.orionkey.constant.OrderType;
import com.orionkey.entity.Order;
import com.orionkey.entity.OrderItem;
import com.orionkey.entity.PaymentChannel;
import com.orionkey.entity.User;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.OrderItemRepository;
import com.orionkey.repository.OrderRepository;
import com.orionkey.repository.PaymentChannelRepository;
import com.orionkey.repository.UserRepository;
import com.orionkey.service.AdminOrderService;
import com.orionkey.service.DistributionService;
import com.orionkey.service.NotificationService;
import com.orionkey.service.UserMessageService;
import com.orionkey.service.WxpayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOrderServiceImpl implements AdminOrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final DistributionService distributionService;
    private final WxpayService wxpayService;
    private final PaymentChannelRepository paymentChannelRepository;
    private final PaymentServiceImpl paymentServiceImpl;
    private final UserMessageService userMessageService;

    @Override
    public PageResult<?> listOrders(String status, String orderType, String paymentMethod,
                                     Boolean isRiskFlagged, String keyword, int page, int pageSize) {
        var pageable = PageRequest.of(page - 1, pageSize);
        // status 支持多选：逗号分隔（如 "PENDING,PAID"），空/null 表示全部
        List<OrderStatus> statuses = new java.util.ArrayList<>();
        boolean containsRefunded = false;
        if (status != null && !status.isBlank()) {
            for (String s : status.split(",")) {
                try {
                    OrderStatus os = OrderStatus.valueOf(s.trim());
                    statuses.add(os);
                    if (os == OrderStatus.REFUNDED) {
                        containsRefunded = true;
                    }
                } catch (IllegalArgumentException e) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的筛选参数: " + s);
                }
            }
        }
        OrderType ot = null;
        try {
            if (orderType != null) ot = OrderType.valueOf(orderType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的筛选参数: " + e.getMessage());
        }
        Page<Order> orderPage;
        String pm = paymentMethod != null && !paymentMethod.isBlank() ? paymentMethod.trim() : "";
        // 未选任何状态时传 null（空列表会使 IN 子句生成非法 SQL，且 IS EMPTY 在 Hibernate 6 对参数不支持）
        List<OrderStatus> statusParam = statuses.isEmpty() ? null : statuses;
        if (keyword != null && !keyword.isBlank()) {
            orderPage = orderRepository.findAdminOrdersByKeyword(statusParam, containsRefunded, ot, pm, isRiskFlagged, "%" + keyword + "%", pageable);
        } else {
            orderPage = orderRepository.findAdminOrders(statusParam, containsRefunded, ot, pm, isRiskFlagged, pageable);
        }

        var list = orderPage.getContent().stream().map(this::toAdminOrder).toList();
        return PageResult.of(orderPage, list);
    }

    @Override
    public Object getOrderDetail(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        return toAdminOrder(order);
    }

    @Override
    @Transactional
    public void markPaid(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅 PENDING 或 EXPIRED 状态订单可标记为已支付");
        }
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(LocalDateTime.now());
        orderRepository.save(order);
        // 分销佣金计算（管理员手动标记已支付也属于支付成功路径）
        try {
            distributionService.onOrderPaid(order.getId());
        } catch (Exception e) {
            log.error("Failed to calculate commission for order {}: {}", order.getId(), e.getMessage());
        }
        // 管理员通知：订单支付成功
        try {
            BigDecimal amount = order.getActualAmount() != null ? order.getActualAmount() : order.getTotalAmount();
            notificationService.sendTemplate("ORDER_PAID", Map.of(
                    "order_no", order.getId().toString().substring(0, 8),
                    "amount", amount != null ? amount.toPlainString() : "0",
                    "payment_method", paymentMethodLabel(order.getPaymentMethod())));
        } catch (Exception e) {
            log.warn("Order paid notification failed: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public Map<String, Object> refund(UUID id, BigDecimal amount, String reason) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));

        // 1. 仅支付后状态（已支付/已发货/已完成）可退款
        OrderStatus status = order.getStatus();
        if (status != OrderStatus.PAID && status != OrderStatus.DELIVERED && status != OrderStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅已支付/已发货/已完成状态的订单可退款");
        }
        // 2. 仅微信原生支付渠道支持原路退回（订单 payment_method 存渠道编码 channel_code，需反查渠道）
        PaymentChannel channel = order.getPaymentMethod() == null ? null
                : paymentChannelRepository.findByChannelCodeAndIsDeleted(order.getPaymentMethod(), 0).orElse(null);
        if (channel == null || !"native_wxpay".equals(channel.getProviderType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅微信支付订单支持在线退款");
        }
        // 3. 校验退款金额
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "退款金额必须大于 0");
        }
        BigDecimal actualAmount = order.getActualAmount() != null ? order.getActualAmount() : BigDecimal.ZERO;
        if (actualAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单实付金额为 0，无法退款");
        }
        BigDecimal alreadyRefunded = order.getRefundedAmount() != null ? order.getRefundedAmount() : BigDecimal.ZERO;
        BigDecimal refundable = actualAmount.subtract(alreadyRefunded);
        if (amount.compareTo(refundable) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "退款金额不能超过订单可退金额（" + refundable.toPlainString() + " 元）");
        }
        // 4. 退款原因必填
        String refundReason = reason == null ? "" : reason.trim();
        if (refundReason.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写退款原因");
        }
        if (refundReason.length() > 200) {
            refundReason = refundReason.substring(0, 200);
        }

        // 5. 读取微信支付渠道配置并发起退款
        if (!channel.isEnabled()) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "微信支付渠道未启用，无法退款");
        }
        WxpayService.WxpayConfig config = paymentServiceImpl.buildWxpayConfig(channel);

        String outRefundNo = "RF" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 10000);
        WxpayService.WxpayRefundResult result = wxpayService.createRefund(
                config, PaymentServiceImpl.formatOutTradeNo(order.getId()), outRefundNo,
                amount, actualAmount, refundReason, null);

        // 6. 更新订单退款信息（微信受理即视为退款成功）
        boolean fullRefund = amount.compareTo(actualAmount) >= 0;
        order.setRefundedAmount(alreadyRefunded.add(amount).setScale(2, java.math.RoundingMode.HALF_UP));
        order.setRefundReason(refundReason);
        order.setOutRefundNo(outRefundNo);
        order.setWxRefundId(result.refundId());
        order.setRefundedAt(LocalDateTime.now());
        order.setStatus(fullRefund ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED);
        orderRepository.save(order);

        // 7. 取消该订单的分销佣金（含已结算的余额扣回）
        try {
            distributionService.cancelCommissions(order.getId());
        } catch (Exception e) {
            log.error("Failed to cancel commissions for refunded order {}: {}", order.getId(), e.getMessage());
        }

        // 8. 通知：用户消息 + 管理员
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("order_no", order.getId().toString().substring(0, 8));
            vars.put("amount", amount.toPlainString());
            vars.put("reason", refundReason);
            userMessageService.sendUserMessage(order.getUserId(), order.getEmail(), "ORDER_REFUNDED", vars);
        } catch (Exception e) {
            log.warn("Failed to send refund user message: {}", e.getMessage());
        }
        try {
            notificationService.sendTemplate("ORDER_REFUNDED", Map.of(
                    "order_no", order.getId().toString().substring(0, 8),
                    "amount", amount.toPlainString(),
                    "reason", refundReason));
        } catch (Exception e) {
            log.warn("Failed to notify admin for order refund: {}", e.getMessage());
        }

        log.info("Order {} refunded: amount={}, fullRefund={}, status={}, outRefundNo={}",
                order.getId(), amount, fullRefund, order.getStatus(), outRefundNo);

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("refunded_amount", order.getRefundedAmount());
        resultMap.put("out_refund_no", outRefundNo);
        resultMap.put("wx_refund_id", result.refundId());
        resultMap.put("status", order.getStatus().name());
        return resultMap;
    }

    private static String paymentMethodLabel(String method) {
        if (method == null || method.isBlank()) return "";
        return switch (method) {
            case "native_wxpay" -> "微信支付";
            case "native_alipay" -> "支付宝";
            case "epay" -> "易支付";
            case "balance" -> "余额支付";
            default -> method.startsWith("usdt_") ? "USDT 链上转账" : method;
        };
    }

    private Map<String, Object> toAdminOrder(Order o) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", o.getId());
        map.put("total_amount", o.getTotalAmount());
        map.put("actual_amount", o.getActualAmount());
        map.put("status", o.getStatus().name());
        map.put("order_type", o.getOrderType().name());
        map.put("payment_method", o.getPaymentMethod());
        // 支付渠道 provider_type（native_wxpay/native_alipay/epay/usdt），供前端判断是否可发起退款
        if (o.getPaymentMethod() != null) {
            map.put("provider_type", paymentChannelRepository
                    .findByChannelCodeAndIsDeleted(o.getPaymentMethod(), 0)
                    .map(PaymentChannel::getProviderType)
                    .orElse(null));
        } else {
            map.put("provider_type", null);
        }
        map.put("created_at", o.getCreatedAt());
        map.put("email", o.getEmail());
        map.put("points_deducted", o.getPointsDeducted());
        map.put("points_discount", o.getPointsDiscount());
        map.put("expires_at", o.getExpiresAt());
        map.put("paid_at", o.getPaidAt());
        map.put("delivered_at", o.getDeliveredAt());
        map.put("completed_at", o.getCompletedAt());
        map.put("refunded_amount", o.getRefundedAmount() != null ? o.getRefundedAmount() : BigDecimal.ZERO);
        map.put("refund_reason", o.getRefundReason());
        map.put("refunded_at", o.getRefundedAt());
        map.put("user_id", o.getUserId());
        map.put("is_risk_flagged", o.isRiskFlagged());

        if (o.getUserId() != null) {
            userRepository.findById(o.getUserId()).ifPresent(u -> map.put("username", u.getUsername()));
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(o.getId());
        map.put("items", items.stream().map(i -> {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("id", i.getId());
            im.put("product_id", i.getProductId());
            im.put("product_title", i.getProductTitle());
            im.put("spec_name", i.getSpecName());
            im.put("quantity", i.getQuantity());
            im.put("unit_price", i.getUnitPrice());
            im.put("subtotal", i.getSubtotal());
            return im;
        }).toList());
        return map;
    }
}
