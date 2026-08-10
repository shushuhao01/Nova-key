package com.orionkey.entity;

import com.orionkey.constant.WithdrawalStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 提现记录
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "withdrawal_records")
public class WithdrawalRecord extends BaseEntity {

    @Column(nullable = false)
    private UUID distributorId;

    /** 提现金额 */
    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    /** 手续费 */
    @Column(precision = 12, scale = 2)
    private BigDecimal fee;

    /** 实到金额 */
    @Column(precision = 12, scale = 2)
    private BigDecimal actualAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WithdrawalStatus status = WithdrawalStatus.PENDING;

    /** 商户单号（微信转账用） */
    @Column(length = 64)
    private String outBillNo;

    /** 微信转账单号 */
    @Column(length = 64)
    private String transferBillNo;

    /** 微信返回的 package_info（拉起确认收款用） */
    @Column(columnDefinition = "TEXT")
    private String packageInfo;

    /** 失败原因 */
    @Column(length = 500)
    private String failReason;

    private LocalDateTime appliedAt;

    private LocalDateTime approvedAt;

    private LocalDateTime transferredAt;

    private LocalDateTime completedAt;
}
