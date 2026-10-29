package com.orionkey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 营销邮件收件人记录（发送时按收件人快照，用于列表中的"发送用户"超链接弹窗展示）。
 * 一个营销邮件对应多条收件人记录；发送后记录送达状态与分配的优惠券核销码。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "marketing_recipients")
public class MarketingRecipient extends BaseEntity {

    /** 所属营销邮件记录 ID */
    @Column(nullable = false)
    private UUID campaignId;

    /** 注册用户 ID（纯邮箱受众时为 null） */
    private UUID userId;

    /** 收件邮箱 */
    @Column(nullable = false)
    private String email;

    /** 收件人用户名快照（纯邮箱受众时为邮箱前缀） */
    private String username;

    /** 分配给该收件人的优惠券核销码（营销邮件关联优惠券时） */
    private String code;

    /** 送达状态：0=待发送/失败，1=已送达 */
    private int delivered = 0;

    /** 发送失败原因 */
    @Column(columnDefinition = "TEXT")
    private String error;

    /** 实际发送时间 */
    private LocalDateTime sentAt;

    /** 首次打开时间（邮件内追踪像素加载时记录） */
    private LocalDateTime openedAt;

    /** 打开次数（同一收件人多次加载像素累加） */
    @Column(columnDefinition = "integer not null default 0")
    private int openedCount = 0;

    /** 首次点击时间（邮件内链接经追踪跳转时记录） */
    private LocalDateTime clickedAt;

    /** 点击次数 */
    @Column(columnDefinition = "integer not null default 0")
    private int clickedCount = 0;

    /** 是否退信：0=否，1=退信（IMAP 解析 DSN 退信邮件后回写） */
    @Column(columnDefinition = "integer not null default 0")
    private int bounced = 0;

    /** 退信时间 */
    private LocalDateTime bouncedAt;

    /** 退信原因（DSN 诊断信息摘要） */
    @Column(columnDefinition = "TEXT")
    private String bounceReason;
}
