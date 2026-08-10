package com.orionkey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 客户绑定关系（推广员 × 客户）
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "customer_bindings", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"customer_email", "distributor_id"})
})
public class CustomerBinding extends BaseEntity {

    @Column(nullable = false)
    private UUID distributorId;

    /** 客户用户 ID（注册用户） */
    private UUID customerUserId;

    /** 客户邮箱（匿名购买用户） */
    @Column(nullable = false)
    private String customerEmail;

    /** 绑定来源商品 ID（null = 全店推广） */
    private UUID productId;

    /** 绑定来源推广链接 ID */
    private UUID promotionLinkId;

    /** 保护期截止时间（过期后可被新推广员抢绑） */
    @Column(nullable = false)
    private LocalDateTime protectionExpiresAt;

    /** 该客户在此推广员名下的购买次数（用于阶梯佣金） */
    @Column(columnDefinition = "integer default 0")
    private int purchaseCount = 0;

    /** 最后一次购买时间 */
    private LocalDateTime lastPurchaseAt;
}
