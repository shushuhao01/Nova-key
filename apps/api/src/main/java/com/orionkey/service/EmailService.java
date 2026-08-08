package com.orionkey.service;

import java.util.UUID;

public interface EmailService {

    void sendDeliveryEmail(UUID orderId);

    /** 发送测试邮件（失败抛异常，由 Controller 转为提示信息） */
    void sendTestEmail(String toEmail);

    /**
     * 发送通知类邮件到指定收件人（管理员通知用，复用后台 SMTP 配置）。
     * 失败抛异常，由调用方决定是否记录日志。
     *
     * @param to      收件邮箱（可逗号分隔多个）
     * @param subject 邮件主题
     * @param content 邮件正文（支持简单 HTML）
     */
    void sendNoticeEmail(String to, String subject, String content);
}
