package com.orionkey.service;

import java.util.Map;

public interface WebhookService {

    /**
     * 处理易支付 GET 回调
     */
    String processEpayCallback(Map<String, String> params);

    /**
     * 处理 BEpusdt USDT 支付回调（JSON body，含非 String 类型字段如 amount/status）
     */
    String processBepusdtCallback(Map<String, Object> params);

    /**
     * 处理原生微信支付 APIv3 回调（含平台证书验签与资源解密）。
     *
     * @param headers 回调请求头（键已转为小写，含 wechatpay-* 签名头）
     * @param rawBody 原始请求体
     * @return "SUCCESS" 表示已确认处理，其他值触发微信重试
     */
    String processWxpayCallback(Map<String, String> headers, String rawBody);

    /**
     * 处理原生支付宝异步通知（form-urlencoded）。
     *
     * @param params 回调参数（含 sign / sign_type）
     * @return "success" 表示已确认处理，其他值触发支付宝重试
     */
    String processAlipayCallback(Map<String, String> params);
}
