package com.orionkey.repository;

import com.orionkey.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    List<OrderItem> findByOrderId(UUID orderId);

    /** 批量查询多个订单的商品明细（订单导出使用，避免逐单查询） */
    List<OrderItem> findByOrderIdIn(Collection<UUID> orderIds);

    @Query("SELECT COALESCE(SUM(oi.quantity), 0) FROM OrderItem oi JOIN Order o ON oi.orderId = o.id " +
            "WHERE oi.productId = :productId AND (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED)")
    int sumQuantityByProductId(@Param("productId") UUID productId);
}
