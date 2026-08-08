package com.orionkey.service;

import com.orionkey.dto.PaymentTestResult;

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
     * @param signType        签名类型（默认 RSA2，支付宝目前仅支持 RSA2）
     */
    record AlipayConfig(String appId, String privateKey, String alipayPublicKey,
                        String gatewayUrl, String notifyUrl, String signType) {
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
     * 测试商户配置与支付宝开放平台的连通性：使用一笔不存在的订单号调用
     * alipay.trade.query，验证 AppID、商家私钥签名是否有效（判定标准与 CRM 一致）。
     * <p>
     * 逐项返回检测清单（AppID / 商家私钥 / 支付宝公钥 / 签名类型 / 连接测试 / 响应验签）：
     * 「连接测试」= 支付宝网关是否接受商家私钥签名；
     * 「响应验签」= 用填写的公钥验证支付宝响应签名，用于校验填的是否真的是「支付宝公钥」
     * （填成应用公钥会导致支付回调验签失败、无法发货）。
     * 单项失败不会抛异常，而是通过 items.status 体现，便于前端展示 ✅/❌。
     *
     * @return 结构化测试结果
     */
    PaymentTestResult testConnection(AlipayConfig config);

    /**
     * 验证支付宝异步通知签名（RSA2）。
     *
     * @param alipayPublicKey 支付宝公钥
     * @param params          回调参数（含 sign / sign_type）
     * @param sign            参数中的签名字段
     */
    boolean verifySign(String alipayPublicKey, Map<String, String> params, String sign);
}
