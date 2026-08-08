package com.orionkey.service;

import java.util.Map;

/**
 * 消息通知服务：预设模板管理、渠道管理（钉钉/企业微信/邮件）、
 * 事件触发通知（注册/下单/支付/发货等）、系统消息（后台铃铛）以及定时报表。
 */
public interface NotificationService {

    /**
     * 触发指定模板的通知：渲染变量 → 写入系统消息（铃铛）→ 按模板勾选的渠道分发
     * （钉钉/企业微信 webhook、邮件）。模板未启用或不存在的渠道静默跳过。
     * 发送过程异步执行且异常不影响主业务流程。
     *
     * @param code 模板编码（如 REGISTER / ORDER_PAID）
     * @param vars 模板变量（{xxx} 占位符替换），会自动补充 {site_name}、{time}
     */
    void sendTemplate(String code, Map<String, Object> vars);
}
