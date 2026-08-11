package com.orionkey.entity;

import com.orionkey.constant.CommissionStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 佣金记录
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "commission_records")
public class CommissionRecord extends BaseEntity {

    @Column(nullable = false)
    private UUID distributorId;

    @Column(nullable = false)
    private UUID orderId;

    private UUID orderItemId;

    @Column(nullable = false)
    private UUID productId;

    @Column(length = 255)
    private String productTitle;

    /** 订单项金额 */
    @Column(precision = 12, scale = 2)
    private BigDecimal orderAmount;

    /** 佣金比例（快照） */
    @Column(precision = 5, scale = 4)
    private BigDecimal commissionRate;

    /** 佣金金额 */
    @Column(precision = 12, scale = 2)
    private BigDecimal commissionAmount;

    /** 阶梯第几单（1=第一单） */
    @Column(columnDefinition = "integer default 1")
    private int tierOrder = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommissionStatus status = CommissionStatus.PENDING;

    private LocalDateTime settledAt;

    /** 关联提现记录（申请提现时写入；提现拒绝后可被新提现单覆盖） */
    private UUID withdrawalId;

    /** 上级分销员 ID（如有抽成） */
    private UUID parentDistributorId;

    /** 上级抽成金额 */
    @Column(precision = 12, scale = 2)
    private BigDecimal parentCommissionAmount;

    /**
     * 是否可结算（展示用，非持久化）：PENDING 状态下，订单已完成且完成时间超过结算延迟期即为可结算。
     * 由服务层批量计算（enrichSettlable），未计算时视为 false。
     */
    @Transient
    private Boolean settlable;
}
