package com.orionkey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 渠道链接点击记录（每次访问 /c/{code} 记一条）。
 * <p>
 * 用于区间点击趋势与独立点击（按 IP 去重）统计。
 * 表结构由 Hibernate ddl-auto=update 自动创建/更新。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "channel_clicks", indexes = {
        @Index(name = "idx_channel_clicks_code", columnList = "channel_code"),
        @Index(name = "idx_channel_clicks_created_at", columnList = "created_at")
})
public class ChannelClick extends BaseEntity {

    /** 渠道码 */
    @Column(name = "channel_code", nullable = false, length = 32)
    private String channelCode;

    /** 点击来源 IP */
    @Column(length = 64)
    private String ip;

    /** User-Agent 原文 */
    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** 来源页 */
    @Column(length = 512)
    private String referer;
}
