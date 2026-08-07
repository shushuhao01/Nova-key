package com.orionkey.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 原生支付宝（当面付预下单 / WAP 手机网站支付）。
 */
public interface AlipayService {

    /**
     * 支付宝渠道配置（从管理后台支付渠道 config_data 读取）。
     *
     * @param appId           开放平台应用 AppID
     * @param privateKey      应用私钥 PEM 内容（RSA2，支持 PKCS#8 / PKCS#1）
     * @param alipayPublicKey 支付宝公钥 PEM 内容
     * @param gatewayUrl      网关地址（默认 https://openapi.alipay.com/gateway.do）
     * @param notifyUrl       异步回调地址
     */
    record AlipayConfig(String appId, String privateKey, String alipayPublicKey,
                        String gatewayUrl, String notifyUrl) {
    }

    record AlipayPaymentResult(String qrCode) {
    }

    record AlipayOrderQueryResult(String tradeStatus, String totalAmount, String tradeNo) {
    }

    /**
     * 当面付预下单（alipay.trade.precreate），返回二维码内容 qr_code。
     */
    AlipayPaymentResult createPrecreate(AlipayConfig config, String outTradeNo, String subject, BigDecimal amount);

    /**
     * 手机网站支付（alipay.trade.wap.pay），返回可直接跳转的支付页 URL。
     */
    String buildWapPayUrl(AlipayConfig config, String outTradeNo, String subject, BigDecimal amount);

    /**
     * 主动查询订单状态（alipay.trade.query）。查询失败返回 null。
     */
    AlipayOrderQueryResult queryOrder(AlipayConfig config, String outTradeNo);

    /**
     * 验证支付宝异步通知签名（RSA2）。
     *
     * @param alipayPublicKey 支付宝公钥
     * @param params          回调参数（含 sign / sign_type）
     * @param sign            参数中的签名字段
     */
    boolean verifySign(String alipayPublicKey, Map<String, String> params, String sign);
}
