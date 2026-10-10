package com.orionkey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 访客会话（30 分钟不活动自动切分）。
 * <p>
 * 表结构由 Hibernate ddl-auto=update 自动创建/更新。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "visit_sessions", indexes = {
        @Index(name = "idx_visit_sessions_visitor_id", columnList = "visitor_id"),
        @Index(name = "idx_visit_sessions_start_time", columnList = "start_time"),
        @Index(name = "idx_visit_sessions_last_active_at", columnList = "last_active_at"),
        @Index(name = "idx_visit_sessions_channel", columnList = "channel_code")
})
public class VisitSession extends BaseEntity {

    /** 访客标识 */
    @Column(name = "visitor_id", length = 64)
    private String visitorId;

    /** 访客 IP */
    @Column(length = 64)
    private String ip;

    /** 会话内浏览页面数 */
    @Column(name = "page_count")
    private int pageCount = 0;

    /** 会话总时长（秒） */
    @Column(name = "duration_sec")
    private int durationSec = 0;

    /** 是否跳出（仅浏览 1 个页面） */
    @Column(name = "is_bounce")
    private boolean isBounce = false;

    /** 来源分类 */
    @Column(length = 32)
    private String source;

    /** 设备类型 */
    @Column(length = 16)
    private String device;

    /** 入口页 */
    @Column(name = "entry_path", length = 255)
    private String entryPath;

    /** 出口页 */
    @Column(name = "exit_path", length = 255)
    private String exitPath;

    /** 会话开始时间 */
    @Column(name = "start_time")
    private LocalDateTime startTime;

    /** 最后活动时间 */
    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    /** 渠道码（会话入口携带的渠道） */
    @Column(name = "channel_code", length = 32)
    private String channelCode;
}
