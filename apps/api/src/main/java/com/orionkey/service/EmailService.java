package com.orionkey.service;

import java.util.UUID;

public interface EmailService {

    void sendDeliveryEmail(UUID orderId);

    /** 发送测试邮件（失败抛异常，由 Controller 转为提示信息） */
    void sendTestEmail(String toEmail);
}
