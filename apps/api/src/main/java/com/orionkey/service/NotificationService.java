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

    /**
     * 向指定邮箱发送「功能性」模板邮件（如找回密码/验证码登录的验证码）。
     * <p>
     * 与 {@link #sendTemplate(String, Map)} 的区别：收件人是用户本人而非后台通知邮箱，
     * 因此不走渠道分发、不写系统消息、忽略模板 enabled/channels 开关，同步发送（调用方可感知失败）。
     * 模板 title/content 仍复用消息通知模板（可在后台「消息通知」中自定义文案），
     * 模板缺失时使用内置兜底文案。
     *
     * @param code    模板编码（如 PASSWORD_RESET_CODE / LOGIN_CODE）
     * @param toEmail 收件人邮箱
     * @param vars    模板变量，会自动补充 {site_name}、{time}
     */
    void sendTemplateToEmail(String code, String toEmail, Map<String, Object> vars);
}
