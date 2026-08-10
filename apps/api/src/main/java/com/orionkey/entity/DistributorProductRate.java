package com.orionkey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 分销员 × 商品 自定义佣金比例
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "distributor_product_rates", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"distributor_id", "product_id"})
})
public class DistributorProductRate extends BaseEntity {

    @Column(nullable = false)
    private UUID distributorId;

    @Column(nullable = false)
    private UUID productId;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal customRate;
}
