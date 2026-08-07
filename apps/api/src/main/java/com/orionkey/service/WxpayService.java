package com.orionkey.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 原生微信支付（APIv3 Native 扫码支付）。
 */
public interface WxpayService {

    /**
     * 微信支付渠道配置（从管理后台支付渠道 config_data 读取）。
     *
     * @param appid      应用 AppID
     * @param mchid      商户号
     * @param apiV3Key   APIv3 密钥（回调资源 AES-256-GCM 解密）
     * @param serialNo   商户 API 证书序列号
     * @param privateKey 商户 API 私钥 PEM 内容（支持 PKCS#8 / PKCS#1）
     * @param notifyUrl  异步回调地址
     * @param gatewayUrl API 网关地址（默认 https://api.mch.weixin.qq.com）
     */
    record WxpayConfig(String appid, String mchid, String apiV3Key, String serialNo,
                       String privateKey, String notifyUrl, String gatewayUrl) {
    }

    record WxpayPaymentResult(String codeUrl) {
    }

    record WxpayOrderQueryResult(String tradeState, Integer total, String transactionId) {
    }

    /**
     * 微信支付回调通知（已验签并解密）。
     *
     * @param id            通知 ID（幂等键）
     * @param eventType     事件类型（TRANSACTION.SUCCESS 等）
     * @param outTradeNo    商户订单号
     * @param tradeState    交易状态
     * @param total         支付金额（分）
     * @param transactionId 微信支付订单号
     */
    record WxpayNotificationResult(String id, String eventType, String outTradeNo, String tradeState,
                                   Integer total, String transactionId) {
    }

    /**
     * Native 扫码下单，返回 code_url（用户扫码支付链接）。
     */
    WxpayPaymentResult createNativePayment(WxpayConfig config, String outTradeNo, String description,
                                           BigDecimal amount, String clientIp);

    /**
     * 主动查询订单状态。查询失败（网络/网关错误）返回 null。
     */
    WxpayOrderQueryResult queryOrder(WxpayConfig config, String outTradeNo);

    /**
     * 测试商户配置与微信支付平台的连通性（调用 GET /v3/certificates 验证
     * 商户号/证书序列号/商户私钥签名是否有效）。
     *
     * @return 成功消息
     * @throws com.orionkey.exception.BusinessException 失败时抛出，message 为详细原因
     */
    String testConnection(WxpayConfig config);

    /**
     * 验证微信回调签名（平台证书）并解密资源内容。
     *
     * @param headers 回调请求头（键已转为小写，含 wechatpay-* 头）
     * @param rawBody 原始请求体（签名验证对象）
     * @return 解密后的通知内容；验签或解密失败返回 null
     */
    WxpayNotificationResult decryptNotification(WxpayConfig config, Map<String, String> headers, String rawBody);
}
