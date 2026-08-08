package com.orionkey.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 消息通知渠道配置：钉钉机器人 / 企业微信机器人 / 通知邮箱。
 * 每种渠道类型一行，configJson 存放渠道特有配置。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "notification_channels")
public class NotificationChannel extends BaseEntity {

    /** 渠道类型：DINGTALK / WECOM / EMAIL（唯一） */
    @Column(nullable = false, unique = true, length = 32)
    private String channelType;

    /** 渠道名称（如 钉钉机器人 / 企业微信机器人 / 通知邮箱） */
    @Column(nullable = false, length = 64)
    private String name;

    /**
     * 渠道配置 JSON：
     * DINGTALK → {"webhook_url": "https://oapi.dingtalk.com/robot/send?access_token=xxx"}
     * WECOM    → {"webhook_url": "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx"}
     * EMAIL    → {"email_to": "admin@example.com,ops@example.com"}（逗号分隔多个接收邮箱）
     */
    @Column(columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "is_enabled")
    private boolean enabled = true;

    private int sortOrder = 0;
}
