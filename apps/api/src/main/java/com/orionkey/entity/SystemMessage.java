package com.orionkey.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 系统消息（后台顶栏铃铛）：注册/下单/支付/发货/报表等事件写入一条，
 * 管理员在总览页右上角铃铛中查看、标记已读、清空。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "system_messages")
public class SystemMessage extends BaseEntity {

    /** 消息标题 */
    @Column(nullable = false, length = 255)
    private String title;

    /** 消息正文 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 消息类型：ORDER / USER / SYSTEM / REPORT */
    @Column(nullable = false, length = 16)
    private String messageType;

    @Column(name = "is_read")
    private boolean read = false;
}
