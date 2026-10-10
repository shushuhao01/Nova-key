package com.orionkey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 前台访问明细（每次页面访问一条）。
 * <p>
 * 表结构由 Hibernate ddl-auto=update 自动创建/更新。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "visit_logs", indexes = {
        @Index(name = "idx_visit_logs_date_hour", columnList = "visit_date,visit_hour"),
        @Index(name = "idx_visit_logs_date_source", columnList = "visit_date,source"),
        @Index(name = "idx_visit_logs_date_device", columnList = "visit_date,device"),
        @Index(name = "idx_visit_logs_date_path", columnList = "visit_date,path"),
        @Index(name = "idx_visit_logs_ip", columnList = "ip"),
        @Index(name = "idx_visit_logs_visitor_id", columnList = "visitor_id"),
        @Index(name = "idx_visit_logs_channel", columnList = "channel_code")
})
public class VisitLog extends BaseEntity {

    /** 访问日期（用于按天聚合/筛选） */
    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    /** 访问小时（0-23），用于时段分布 */
    @Column(name = "visit_hour", nullable = false)
    private int visitHour;

    /** 访客 IP */
    @Column(length = 64)
    private String ip;

    /** 国家 */
    @Column(length = 64)
    private String country;

    /** 省份/区域 */
    @Column(length = 64)
    private String province;

    /** 城市 */
    @Column(length = 64)
    private String city;

    /** 运营商 */
    @Column(length = 128)
    private String isp;

    /** 设备类型：desktop / mobile / tablet / bot / unknown */
    @Column(length = 16)
    private String device;

    /** 操作系统 */
    @Column(length = 32)
    private String os;

    /** 浏览器 */
    @Column(length = 32)
    private String browser;

    /** 流量来源分类：direct / search / social / external 等 */
    @Column(length = 32)
    private String source;

    /** 来源页完整 URL */
    @Column(length = 512)
    private String referer;

    /** 访问路径 */
    @Column(length = 255)
    private String path;

    /** 访客标识（前端持久化的匿名 ID） */
    @Column(name = "visitor_id", length = 64)
    private String visitorId;

    /** User-Agent 原文 */
    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** 屏幕分辨率 */
    @Column(length = 16)
    private String screen;

    /** 浏览器语言 */
    @Column(length = 16)
    private String lang;

    /** 渠道码（来自渠道链接 /c/{code} 或 ?ch= / ?utm_source= 归一化） */
    @Column(name = "channel_code", length = 32)
    private String channelCode;
}
