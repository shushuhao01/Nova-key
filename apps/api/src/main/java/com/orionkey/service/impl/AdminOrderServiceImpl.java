package com.orionkey.service.impl;

import com.orionkey.common.PageResult;
import com.orionkey.constant.ErrorCode;
import com.orionkey.constant.OrderStatus;
import com.orionkey.constant.OrderType;
import com.orionkey.entity.Order;
import com.orionkey.entity.OrderItem;
import com.orionkey.entity.User;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.OrderItemRepository;
import com.orionkey.repository.OrderRepository;
import com.orionkey.repository.UserRepository;
import com.orionkey.service.AdminOrderService;
import com.orionkey.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOrderServiceImpl implements AdminOrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Override
    public PageResult<?> listOrders(String status, String orderType, String paymentMethod,
                                     Boolean isRiskFlagged, String keyword, int page, int pageSize) {
        var pageable = PageRequest.of(page - 1, pageSize);
        OrderStatus os = null;
        OrderType ot = null;
        try {
            if (status != null) os = OrderStatus.valueOf(status);
            if (orderType != null) ot = OrderType.valueOf(orderType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的筛选参数: " + e.getMessage());
        }
        Page<Order> orderPage;
        String pm = paymentMethod != null && !paymentMethod.isBlank() ? paymentMethod.trim() : "";
        if (keyword != null && !keyword.isBlank()) {
            orderPage = orderRepository.findAdminOrdersByKeyword(os, ot, pm, isRiskFlagged, "%" + keyword + "%", pageable);
        } else {
            orderPage = orderRepository.findAdminOrders(os, ot, pm, isRiskFlagged, pageable);
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
        map.put("created_at", o.getCreatedAt());
        map.put("email", o.getEmail());
        map.put("points_deducted", o.getPointsDeducted());
        map.put("points_discount", o.getPointsDiscount());
        map.put("expires_at", o.getExpiresAt());
        map.put("paid_at", o.getPaidAt());
        map.put("delivered_at", o.getDeliveredAt());
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
