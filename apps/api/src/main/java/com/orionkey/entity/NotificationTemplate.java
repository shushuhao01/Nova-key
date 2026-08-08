package com.orionkey.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 消息通知模板（预设）：注册 / 下单 / 支付 / 发货 / 库存预警 / 日报 / 周报 / 月报等。
 * title/content 支持 {变量} 占位符，由发送方传入实际值替换。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "notification_templates")
public class NotificationTemplate extends BaseEntity {

    /** 模板编码（唯一），如 REGISTER / ORDER_PAID / WEEKLY_REPORT */
    @Column(nullable = false, unique = true, length = 64)
    private String code;

    /** 模板名称（后台展示） */
    @Column(nullable = false, length = 64)
    private String name;

    /** 模板分类：USER / ORDER / SYSTEM / REPORT */
    @Column(nullable = false, length = 16)
    private String category;

    /** 通知标题模板（支持 {变量}） */
    @Column(nullable = false, length = 255)
    private String title;

    /** 通知正文模板（支持 {变量}） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 启用发送的渠道，逗号分隔：DINGTALK,WECOM,EMAIL。
     * 勾选哪个渠道，触发时就通过哪个渠道通知；不勾选则不通知。
     */
    @Column(nullable = false, length = 255)
    private String channels = "DINGTALK,WECOM,EMAIL";

    /** 模板总开关：关闭后该模板不发送任何通知 */
    @Column(name = "is_enabled")
    private boolean enabled = false;

    /** 是否由定时任务自动触发（如日报/周报/月报/库存预警） */
    @Column(name = "auto_trigger")
    private boolean autoTrigger = false;

    private int sortOrder = 0;
}
