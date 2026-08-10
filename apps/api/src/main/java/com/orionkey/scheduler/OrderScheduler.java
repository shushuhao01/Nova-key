package com.orionkey.scheduler;

import com.orionkey.constant.OrderStatus;
import com.orionkey.entity.Order;
import com.orionkey.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单状态定时任务
 *
 * 1. 订单自动完成（每5分钟）— 已发货（DELIVERED）超过 24 小时的订单自动置为已完成（COMPLETED），
 *    便于后续按"订单完成 + N 天"结算分销佣金
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderScheduler {

    /** 自动完成判定：发货后超过 24 小时无其他操作 */
    private static final long AUTO_COMPLETE_HOURS = 24;

    private final OrderRepository orderRepository;

    /**
     * 已发货订单自动完成 — 每5分钟执行
     * DELIVERED 且 deliveredAt 超过 24 小时的订单 → COMPLETED，并记录完成时间。
     */
    @Scheduled(cron = "0 */5 * * * *")
    @Transactional
    public void autoCompleteDeliveredOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(AUTO_COMPLETE_HOURS);
        List<Order> orders = orderRepository.findAutoCompleteOrders(cutoff);
        if (orders.isEmpty()) {
            return;
        }
        log.info("自动完成 {} 笔已发货超过 {} 小时的订单", orders.size(), AUTO_COMPLETE_HOURS);
        for (Order o : orders) {
            try {
                o.setStatus(OrderStatus.COMPLETED);
                o.setCompletedAt(LocalDateTime.now());
                orderRepository.save(o);
                log.info("订单 {} 自动完成（发货时间 {}）", o.getId(), o.getDeliveredAt());
            } catch (Exception e) {
                log.error("订单 {} 自动完成失败: {}", o.getId(), e.getMessage(), e);
            }
        }
    }
}
