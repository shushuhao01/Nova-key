package com.orionkey.entity;

import com.orionkey.constant.DistributorStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "distributors")
public class Distributor extends BaseEntity {

    @Column(nullable = false, unique = true)
    private UUID userId;

    /** 推广员编码，如 D2026081 */
    @Column(nullable = false, unique = true, length = 20)
    private String distributorCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DistributorStatus status = DistributorStatus.PENDING;

    /** 自定义默认佣金比例（null 表示用全局默认） */
    @Column(precision = 5, scale = 4)
    private BigDecimal customRate;

    /** 上级分销员 ID（null 表示无上级） */
    private UUID parentId;

    /** 从下级佣金中抽成的比例 */
    @Column(precision = 5, scale = 4)
    private BigDecimal subRate;

    /** 绑定的微信 openid */
    @Column(length = 64)
    private String wechatOpenid;

    @Column(length = 64)
    private String wechatUnionid;

    @Column(length = 64)
    private String wechatNickname;

    private LocalDateTime wechatBoundAt;

    /** 累计佣金 */
    @Column(precision = 12, scale = 2, columnDefinition = "decimal(12,2) default 0")
    private BigDecimal totalCommission = BigDecimal.ZERO;

    /** 可提现余额 */
    @Column(precision = 12, scale = 2, columnDefinition = "decimal(12,2) default 0")
    private BigDecimal availableBalance = BigDecimal.ZERO;

    /** 冻结中余额（提现申请中） */
    @Column(precision = 12, scale = 2, columnDefinition = "decimal(12,2) default 0")
    private BigDecimal frozenBalance = BigDecimal.ZERO;

    /** 已提现总额 */
    @Column(precision = 12, scale = 2, columnDefinition = "decimal(12,2) default 0")
    private BigDecimal withdrawnAmount = BigDecimal.ZERO;

    private LocalDateTime approvedAt;

    private LocalDateTime disabledAt;

    /** 拒绝原因（审核拒绝时填写，用户前台可见） */
    @Column(columnDefinition = "TEXT")
    private String rejectReason;

    /** 拒绝时间 */
    private LocalDateTime rejectedAt;

    /** 邀请码（用于下级扫码加入） */
    @Column(length = 16)
    private String inviteCode;
}
