package com.orionkey.entity;

import com.orionkey.constant.UserMessageCategory;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户消息（前台铃铛）
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_messages")
public class UserMessage extends BaseEntity {

    /** 接收用户（注册用户） */
    private UUID userId;

    /** 接收邮箱（匿名用户，仅邮件通知） */
    @Column(length = 255)
    private String email;

    @Column(length = 64)
    private String templateCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserMessageCategory category = UserMessageCategory.SYSTEM;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "boolean not null default false")
    private boolean read = false;

    /** JSON 附加数据（如订单ID、金额等，用于跳转） */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    private LocalDateTime readAt;
}
