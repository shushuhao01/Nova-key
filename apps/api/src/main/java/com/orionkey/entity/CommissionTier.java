package com.orionkey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 阶梯佣金配置（同一客户多次购买佣金递减）
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "commission_tiers")
public class CommissionTier extends BaseEntity {

    /** 第几次购买（1=第一次） */
    @Column(nullable = false)
    private int tierOrder;

    /** 佣金比例（相对基础佣金的比例，1.0=100%，0.5=50%） */
    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal rate;

    @Column(columnDefinition = "boolean not null default true")
    private boolean enabled = true;
}
