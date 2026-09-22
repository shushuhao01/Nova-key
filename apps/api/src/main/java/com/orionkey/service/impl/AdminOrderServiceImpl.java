package com.orionkey.service.impl;

import com.orionkey.common.PageResult;
import com.orionkey.constant.ErrorCode;
import com.orionkey.constant.OrderStatus;
import com.orionkey.constant.OrderType;
import com.orionkey.entity.Order;
import com.orionkey.entity.OrderItem;
import com.orionkey.entity.PaymentChannel;
import com.orionkey.entity.Distributor;
import com.orionkey.entity.User;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.DistributorRepository;
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
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOrderServiceImpl implements AdminOrderService {

    /** 退款处理中：已发起、等待微信确认到账（订单状态保持不变） */
    private static final String REFUND_STATUS_PENDING = "PENDING";
    /** 退款成功（终态） */
    private static final String REFUND_STATUS_SUCCESS = "SUCCESS";
    /** 退款未成功（关闭/异常，终态） */
    private static final String REFUND_STATUS_FAILED = "FAILED";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final DistributorRepository distributorRepository;
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

        // 批量解析推广员（订单 referral_distributor_id → 分销员账号/用户名），避免 N+1
        Map<UUID, String> promoterMap = resolvePromoters(orderPage.getContent().stream()
                .map(Order::getReferralDistributorId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
        var list = orderPage.getContent().stream().map(o -> toAdminOrder(o, promoterMap)).toList();
        return PageResult.of(orderPage, list);
    }

    @Override
    public Object getOrderDetail(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        Map<UUID, String> promoterMap = order.getReferralDistributorId() != null
                ? resolvePromoters(Set.of(order.getReferralDistributorId()))
                : Map.of();
        return toAdminOrder(order, promoterMap);
    }

    /** 批量解析分销员 id → 展示名（优先用户名，其次邮箱，其次分销编号） */
    private Map<UUID, String> resolvePromoters(Set<UUID> distIds) {
        if (distIds.isEmpty()) return Map.of();
        List<Distributor> distributors = distributorRepository.findAllById(distIds);
        if (distributors.isEmpty()) return Map.of();
        Set<UUID> userIds = distributors.stream().map(Distributor::getUserId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Map<UUID, String> userNames = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream().collect(java.util.stream.Collectors.toMap(User::getId,
                        u -> u.getUsername() != null ? u.getUsername() : u.getEmail()));
        Map<UUID, String> result = new HashMap<>();
        for (Distributor d : distributors) {
            String name = d.getUserId() != null ? userNames.get(d.getUserId()) : null;
            if (name == null || name.isBlank()) name = d.getDistributorCode();
            result.put(d.getId(), name);
        }
        return result;
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
        // 2. 已有退款处理中（已发起、微信尚未确认到账）时不允许再次发起，避免重复/超额退款
        if (REFUND_STATUS_PENDING.equals(order.getRefundStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该订单有退款正在处理中，请等待退款结果确认后再操作");
        }
        // 3. 仅微信原生支付渠道支持原路退回（订单 payment_method 存渠道编码 channel_code，需反查渠道）
        PaymentChannel channel = order.getPaymentMethod() == null ? null
                : paymentChannelRepository.findByChannelCodeAndIsDeleted(order.getPaymentMethod(), 0).orElse(null);
        if (channel == null || !"native_wxpay".equals(channel.getProviderType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅微信支付订单支持在线退款");
        }
        // 4. 校验退款金额
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
        // 退款必须针对实际支付时使用的单号：微信内 JSAPI 支付用的是独立单号，扫码支付用订单号派生的单号
        String refundTradeNo = order.getJsapiTradeNo() != null && !order.getJsapiTradeNo().isBlank()
                ? order.getJsapiTradeNo() : PaymentServiceImpl.formatOutTradeNo(order.getId());
        WxpayService.WxpayRefundResult result = wxpayService.createRefund(
                config, refundTradeNo, outRefundNo,
                amount, actualAmount, refundReason, paymentServiceImpl.buildWxpayRefundNotifyUrl());

        // 6. 更新订单退款信息
        //    微信同步返回 SUCCESS 表示退款已成功（终态），直接落终态；
        //    返回 PROCESSING 仅表示已受理、尚未到账，此时订单状态保持不变，退款状态置为 PENDING，
        //    等待退款结果通知或定时回查确认到账（避免"受理即视为成功"造成资损）
        order.setRefundReason(refundReason);
        order.setOutRefundNo(outRefundNo);
        order.setWxRefundId(result.refundId());
        boolean refundSucceeded = REFUND_STATUS_SUCCESS.equals(result.status());
        if (refundSucceeded) {
            order.setRefundedAmount(alreadyRefunded.add(amount).setScale(2, RoundingMode.HALF_UP));
            order.setRefundStatus(REFUND_STATUS_SUCCESS);
            order.setRefundedAt(LocalDateTime.now());
            order.setStatus(amount.compareTo(actualAmount) >= 0 ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED);
        } else {
            order.setRefundStatus(REFUND_STATUS_PENDING);
        }
        orderRepository.save(order);

        if (refundSucceeded) {
            // 7. 取消该订单的分销佣金（含已结算的余额扣回）
            cancelCommissionsQuietly(order.getId());
            // 8. 通知：用户消息 + 管理员
            sendRefundNotifications(order, amount, refundReason);
            log.info("Order {} refunded: amount={}, status={}, outRefundNo={}",
                    order.getId(), amount, order.getStatus(), outRefundNo);
        } else {
            log.info("Order {} refund accepted, awaiting confirmation: amount={}, outRefundNo={}, wxStatus={}",
                    order.getId(), amount, outRefundNo, result.status());
        }

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("refunded_amount", order.getRefundedAmount());
        resultMap.put("out_refund_no", outRefundNo);
        resultMap.put("wx_refund_id", result.refundId());
        resultMap.put("status", order.getStatus().name());
        resultMap.put("refund_status", order.getRefundStatus());
        return resultMap;
    }

    @Override
    @Transactional
    public boolean finalizeWxpayRefund(String outRefundNo, String refundStatus, Integer refundCents) {
        Order order = orderRepository.findByOutRefundNo(outRefundNo).orElse(null);
        if (order == null) {
            log.warn("Wxpay refund finalize: order not found, outRefundNo={}", outRefundNo);
            return false;
        }
        // 幂等：已落成功终态时直接确认（微信会重复通知）
        if (REFUND_STATUS_SUCCESS.equals(order.getRefundStatus())) {
            log.info("Wxpay refund finalize: already succeeded, outRefundNo={}", outRefundNo);
            return true;
        }
        if (REFUND_STATUS_SUCCESS.equals(refundStatus)) {
            if (refundCents == null) {
                log.error("Wxpay refund finalize: missing refund amount for SUCCESS, outRefundNo={}", outRefundNo);
                return false;
            }
            BigDecimal amount = BigDecimal.valueOf(refundCents)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal alreadyRefunded = order.getRefundedAmount() != null ? order.getRefundedAmount() : BigDecimal.ZERO;
            BigDecimal refunded = alreadyRefunded.add(amount).setScale(2, RoundingMode.HALF_UP);
            BigDecimal actualAmount = order.getActualAmount() != null ? order.getActualAmount() : BigDecimal.ZERO;
            order.setRefundedAmount(refunded);
            order.setRefundStatus(REFUND_STATUS_SUCCESS);
            order.setRefundedAt(LocalDateTime.now());
            order.setStatus(refunded.compareTo(actualAmount) >= 0
                    ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED);
            orderRepository.save(order);
            cancelCommissionsQuietly(order.getId());
            sendRefundNotifications(order, amount, order.getRefundReason());
            log.info("Order {} refund finalized: outRefundNo={}, amount={}, status={}",
                    order.getId(), outRefundNo, amount, order.getStatus());
        } else {
            // CLOSED / ABNORMAL：本次退款未成功，订单状态保持不变（终态，不触发佣金/通知）
            order.setRefundStatus(REFUND_STATUS_FAILED);
            orderRepository.save(order);
            log.warn("Order {} refund not completed: outRefundNo={}, refundStatus={}",
                    order.getId(), outRefundNo, refundStatus);
        }
        return true;
    }

    @Override
    @Transactional
    public int reconcilePendingRefunds() {
        List<Order> pending = orderRepository.findPendingRefunds();
        if (pending.isEmpty()) {
            return 0;
        }
        int finalized = 0;
        for (Order order : pending) {
            String outRefundNo = order.getOutRefundNo();
            if (outRefundNo == null || outRefundNo.isBlank()) {
                continue;
            }
            try {
                PaymentChannel channel = order.getPaymentMethod() == null ? null
                        : paymentChannelRepository.findByChannelCodeAndIsDeleted(order.getPaymentMethod(), 0)
                                .orElse(null);
                if (channel == null || !"native_wxpay".equals(channel.getProviderType())) {
                    log.warn("Pending refund reconcile skipped: channel unavailable, outRefundNo={}", outRefundNo);
                    continue;
                }
                WxpayService.WxpayRefundQueryResult query = wxpayService.queryRefund(
                        paymentServiceImpl.buildWxpayConfig(channel), outRefundNo);
                if (query == null || query.isError()) {
                    log.warn("Pending refund reconcile deferred: query failed, outRefundNo={}, error={}",
                            outRefundNo, query != null ? query.error() : null);
                    continue;
                }
                // 退款单尚不存在（404）或仍在处理中，留待下轮再查
                if (query.status() == null || "PROCESSING".equals(query.status())) {
                    continue;
                }
                if (finalizeWxpayRefund(outRefundNo, query.status(), query.refundAmount())) {
                    finalized++;
                }
            } catch (Exception e) {
                log.error("Pending refund reconcile failed for outRefundNo={}: {}", outRefundNo, e.getMessage());
            }
        }
        return finalized;
    }

    /** 取消订单分销佣金（失败仅记录日志，不影响退款主流程） */
    private void cancelCommissionsQuietly(UUID orderId) {
        try {
            distributionService.cancelCommissions(orderId);
        } catch (Exception e) {
            log.error("Failed to cancel commissions for refunded order {}: {}", orderId, e.getMessage());
        }
    }

    /** 退款成功通知：用户消息 + 管理员模板通知（失败仅记录日志） */
    private void sendRefundNotifications(Order order, BigDecimal amount, String reason) {
        String orderNo = order.getId().toString().substring(0, 8);
        String safeReason = reason != null ? reason : "";
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("order_no", orderNo);
            vars.put("amount", amount.toPlainString());
            vars.put("reason", safeReason);
            userMessageService.sendUserMessage(order.getUserId(), order.getEmail(), "ORDER_REFUNDED", vars);
        } catch (Exception e) {
            log.warn("Failed to send refund user message: {}", e.getMessage());
        }
        try {
            notificationService.sendTemplate("ORDER_REFUNDED", Map.of(
                    "order_no", orderNo,
                    "amount", amount.toPlainString(),
                    "reason", safeReason));
        } catch (Exception e) {
            log.warn("Failed to notify admin for order refund: {}", e.getMessage());
        }
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

    private Map<String, Object> toAdminOrder(Order o, Map<UUID, String> promoterMap) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", o.getId());
        map.put("total_amount", o.getTotalAmount());
        map.put("actual_amount", o.getActualAmount());
        map.put("status", o.getStatus().name());
        map.put("order_type", o.getOrderType().name());
        map.put("payment_method", o.getPaymentMethod());
        map.put("device", o.getDevice());
        // 推广员：该订单由哪个分销员推荐成交（无则 null，前端显示 -）
        map.put("promoter", o.getReferralDistributorId() != null ? promoterMap.get(o.getReferralDistributorId()) : null);
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
