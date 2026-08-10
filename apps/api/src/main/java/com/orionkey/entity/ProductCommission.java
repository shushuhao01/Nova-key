package com.orionkey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 商品佣金配置
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "product_commissions")
public class ProductCommission extends BaseEntity {

    @Column(nullable = false, unique = true)
    private UUID productId;

    /** 自定义佣金比例（null 表示用全局默认） */
    @Column(precision = 5, scale = 4)
    private BigDecimal customRate;

    /** 是否排除该商品不参与分销 */
    @Column(columnDefinition = "boolean not null default false")
    private boolean excluded = false;
}
