package com.orionkey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 分销规则配置（单行表，id 固定为约定值）
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "distribution_rules")
public class DistributionRule extends BaseEntity {

    /** 默认佣金比例 0.10 = 10% */
    @Column(precision = 5, scale = 4, columnDefinition = "decimal(5,4) default 0.10")
    private BigDecimal defaultRate = new BigDecimal("0.10");

    /** 是否开启分销功能 */
    @Column(columnDefinition = "boolean not null default true")
    private boolean enabled = true;

    /** 是否自动审核分销员 */
    @Column(columnDefinition = "boolean not null default false")
    private boolean autoApprove = false;

    /** 最低提现金额 */
    @Column(precision = 12, scale = 2, columnDefinition = "decimal(12,2) default 10.00")
    private BigDecimal minWithdrawAmount = new BigDecimal("10.00");

    /** 订单完成后 N 天结算佣金 */
    @Column(columnDefinition = "integer default 7")
    private int settleDelayDays = 7;

    /** 提现手续费率（0 = 免费） */
    @Column(precision = 5, scale = 4, columnDefinition = "decimal(5,4) default 0")
    private BigDecimal withdrawFeeRate = BigDecimal.ZERO;

    /** 客户绑定保护期（天） */
    @Column(columnDefinition = "integer default 30")
    private int bindingProtectionDays = 30;

    /** 是否启用阶梯佣金 */
    @Column(columnDefinition = "boolean not null default false")
    private boolean tierEnabled = false;

    /** 是否启用二级分销 */
    @Column(columnDefinition = "boolean not null default true")
    private boolean subDistributionEnabled = true;

    /** 默认下级佣金抽成比例 */
    @Column(precision = 5, scale = 4, columnDefinition = "decimal(5,4) default 0.30")
    private BigDecimal defaultSubRate = new BigDecimal("0.30");
}
