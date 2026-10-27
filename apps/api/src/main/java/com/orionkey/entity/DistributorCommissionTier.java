package com.orionkey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 推广员级阶梯佣金配置（同一客户在该推广员名下多次购买佣金递减）。
 * 未配置（无记录）时沿用全局 commission_tiers。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "distributor_commission_tiers",
        uniqueConstraints = {@UniqueConstraint(columnNames = {"distributor_id", "tier_order"})})
public class DistributorCommissionTier extends BaseEntity {

    @Column(nullable = false)
    private UUID distributorId;

    /** 第几次购买（1=第一次） */
    @Column(nullable = false)
    private int tierOrder;

    /** 佣金比例（相对基础佣金的比例，1.0=100%，0.5=50%） */
    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal rate;

    @Column(columnDefinition = "boolean not null default true")
    private boolean enabled = true;
}
