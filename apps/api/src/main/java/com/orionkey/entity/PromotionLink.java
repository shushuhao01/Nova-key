package com.orionkey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 推广链接
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "promotion_links")
public class PromotionLink extends BaseEntity {

    @Column(nullable = false)
    private UUID distributorId;

    /** 商品 ID（null = 全店推广） */
    private UUID productId;

    @Column(nullable = false, unique = true, length = 16)
    private String linkCode;

    /** 点击次数 */
    @Column(columnDefinition = "integer default 0")
    private int clickCount = 0;

    /** 独立访客数 */
    @Column(columnDefinition = "integer default 0")
    private int uniqueClickCount = 0;

    /** 付款订单数 */
    @Column(columnDefinition = "integer default 0")
    private int paidCount = 0;

    /** 总销售额 */
    @Column(precision = 12, scale = 2, columnDefinition = "decimal(12,2) default 0")
    private BigDecimal totalSales = BigDecimal.ZERO;

    /** 总佣金 */
    @Column(precision = 12, scale = 2, columnDefinition = "decimal(12,2) default 0")
    private BigDecimal totalCommission = BigDecimal.ZERO;
}
