package com.orionkey.service;

import java.util.Map;

/**
 * 短信服务（阿里云）。
 * <p>
 * 约定：未启用或未配置时<b>不允许伪造成功</b>，调用方需按异常提示用户。
 */
public interface SmsService {

    /** 短信服务是否已启用（sms_enabled = true） */
    boolean isEnabled();

    /** 短信服务配置是否完整（AK / SK / 签名齐全） */
    boolean isConfigured();

    /**
     * 按模板发送短信。
     *
     * @param phone        接收手机号（中国大陆 11 位）
     * @param templateCode 阿里云短信模板 CODE
     * @param params       模板变量（key 与模板正文 ${key} 逐字一致）
     */
    void sendTemplate(String phone, String templateCode, Map<String, Object> params);

    /**
     * 发送验证码短信：按场景取模板 CODE，并组装 code / site_name 变量。
     *
     * @param scene 场景（REGISTER / LOGIN / PASSWORD_RESET / BIND_PHONE）
     */
    void sendVerifyCode(String phone, String scene, String code, int expireMinutes);

    /** 场景对应的短信模板 CODE，未配置返回 null */
    String templateCodeForScene(String scene);

    /** 测试连通：校验 AK/SK + 签名（调用阿里云只读接口，权限不足时退化为发送测试短信） */
    Map<String, Object> testConnection();

    /** 测试发送：向指定手机号发送指定模板（templateCode 为空时使用通用验证码模板） */
    Map<String, Object> testSend(String phone, String templateCode);
}
