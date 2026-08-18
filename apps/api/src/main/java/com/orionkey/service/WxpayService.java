package com.orionkey.service;

import com.orionkey.dto.PaymentTestResult;

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
     * @param transferSceneId 商家转账场景ID（如 1005=佣金报酬），用于分销提现
     * @param appSecret  公众号 AppSecret（微信网页授权换取 openid 用，分销员绑定微信）
     */
    record WxpayConfig(String appid, String mchid, String apiV3Key, String serialNo,
                       String privateKey, String notifyUrl, String gatewayUrl,
                       String transferSceneId, String appSecret) {
    }

    record WxpayPaymentResult(String codeUrl, String h5Url) {
        /** Native 扫码结果（只有 codeUrl） */
        public WxpayPaymentResult(String codeUrl) {
            this(codeUrl, null);
        }
        /** H5 跳转结果（只有 h5Url） */
        public static WxpayPaymentResult h5(String h5Url) {
            return new WxpayPaymentResult(null, h5Url);
        }
        /** 是否为 H5 支付结果 */
        public boolean isH5() {
            return h5Url != null && !h5Url.isBlank();
        }
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
     * 商家转账到零钱结果。
     *
     * @param outBillNo      商户单号
     * @param transferBillNo 微信转账单号
     * @param state          转账状态（ACCEPTED / PROCESSING / WAIT_USER_CONFIRM 等）
     * @param packageInfo    拉起用户确认收款所需的 package_info（JSAPI 调起用）
     */
    record WxpayTransferResult(String outBillNo, String transferBillNo, String state, String packageInfo) {
    }

    /**
     * 商家转账结果回调通知（已验签并解密）。
     *
     * @param id        通知 ID（幂等键）
     * @param outBillNo 商户转账单号
     * @param state     转账终态（FINISHED=成功，其他如 FAILED/CLOSED/WAIT_USER_CONFIRM 等）
     * @param failReason 失败原因
     */
    record WxpayTransferNotificationResult(String id, String outBillNo, String state, String failReason) {
    }

    /**
     * 商家转账状态查询结果（GET /v3/transfer/batches/out-bill-no/{out_bill_no}）。
     *
     * @param state          转账批次状态（ACCEPTED / PROCESSING / FINISHED / CLOSED）
     * @param transferBillNo 微信转账单号
     * @param failReason     失败原因（成功时为空）
     * @param transferAmount 转账金额（分）
     */
    record WxpayTransferQueryResult(String state, String transferBillNo, String failReason, Integer transferAmount) {
    }

    /**
     * 微信支付退款结果。
     *
     * @param refundId    微信退款单号（refund_id）
     * @param outRefundNo 商户退款单号（out_refund_no）
     * @param status      退款状态（SUCCESS=退款成功 / PROCESSING=退款处理中 / CLOSED=退款关闭 / ABNORMAL=退款异常）
     */
    record WxpayRefundResult(String refundId, String outRefundNo, String status) {
    }

    /**
     * Native 扫码下单，返回 code_url（用户扫码支付链接）。
     */
    WxpayPaymentResult createNativePayment(WxpayConfig config, String outTradeNo, String description,
                                           BigDecimal amount, String clientIp);

    /**
     * H5 支付下单，返回 h5_url（移动端浏览器直接跳转拉起微信 App）。
     * 仅适用于非微信浏览器的移动端（Safari/Chrome 等），微信内需用 JSAPI。
     */
    WxpayPaymentResult createH5Payment(WxpayConfig config, String outTradeNo, String description,
                                       BigDecimal amount, String clientIp);

    /**
     * 主动查询订单状态。查询失败（网络/网关错误）返回 null。
     */
    WxpayOrderQueryResult queryOrder(WxpayConfig config, String outTradeNo);

    /**
     * 微信支付退款（POST /v3/refund/domestic/refunds），原路退回用户。
     * <p>
     * 用于管理后台订单退款：对已支付（PAID/DELIVERED/COMPLETED）的微信支付订单发起全额或部分退款。
     * 退款金额（refundAmount）不能超过原订单实付金额（totalAmount），均以元为单位。
     *
     * @param config        微信支付配置
     * @param outTradeNo    原商户订单号（与下单时一致）
     * @param outRefundNo   商户退款单号（唯一，幂等键）
     * @param refundAmount  退款金额（元）
     * @param totalAmount   原订单实付金额（元）
     * @param reason        退款原因（≤80 字符，选填但建议填写）
     * @param notifyUrl     退款结果异步回调地址（可空，空则不传）
     * @return 退款结果（含微信退款单号与受理状态）
     */
    WxpayRefundResult createRefund(WxpayConfig config, String outTradeNo, String outRefundNo,
                                   BigDecimal refundAmount, BigDecimal totalAmount,
                                   String reason, String notifyUrl);

    /**
     * 商家转账到零钱（POST /v3/transfer/batches）。
     * <p>
     * 用于分销佣金提现，管理员审批通过后调用此接口发起转账。
     * 返回 package_info 后，前端在微信内通过 WeixinJSBridge.invoke('requestPayment') 拉起用户确认收款。
     *
     * @param config          微信支付配置（含 transferSceneId）
     * @param outBillNo       商户单号（唯一，幂等键）
     * @param openid          收款用户 openid
     * @param amount          转账金额（元）
     * @param remark          转账备注（如"佣金提现"）
     * @param notifyUrl       转账结果异步回调地址
     * @return 转账结果（含 package_info）
     */
    WxpayTransferResult createTransfer(WxpayConfig config, String outBillNo, String openid,
                                       BigDecimal amount, String remark, String notifyUrl);

    /**
     * 查询商家转账状态（GET /v3/transfer/batches/out-bill-no/{out_bill_no}）。
     * <p>
     * 用于提现兜底：转账回调丢失时，由管理后台主动查询转账终态，失败返回 null。
     *
     * @param config    微信支付配置
     * @param outBillNo 商户转账单号
     * @return 转账状态；查询失败（网络/网关错误）返回 null
     */
    WxpayTransferQueryResult queryTransfer(WxpayConfig config, String outBillNo);

    /**
     * 测试商户配置与微信支付平台的连通性（调用 GET /v3/certificates 验证
     * 商户号/证书序列号/商户私钥签名是否有效）。
     * <p>
     * 逐项返回检测清单（AppID / 商户号 / API密钥 / 证书 / 连接测试），
     * 单项失败不会抛异常，而是通过 items.status 体现，便于前端展示 ✅/❌。
     *
     * @return 结构化测试结果
     */
    PaymentTestResult testConnection(WxpayConfig config);

    /**
     * 验证微信回调签名（平台证书）并解密资源内容。
     *
     * @param headers 回调请求头（键已转为小写，含 wechatpay-* 头）
     * @param rawBody 原始请求体（签名验证对象）
     * @return 解密后的通知内容；验签或解密失败返回 null
     */
    WxpayNotificationResult decryptNotification(WxpayConfig config, Map<String, String> headers, String rawBody);

    /**
     * 验证微信商家转账结果回调签名并解密资源内容。
     * 与 {@link #decryptNotification} 使用相同的 APIv3 通知机制，但资源字段为
     * out_bill_no / state / fail_reason（转账结果），而非支付订单字段。
     *
     * @param headers 回调请求头（键已转为小写，含 wechatpay-* 头）
     * @param rawBody 原始请求体（签名验证对象）
     * @return 解密后的转账通知内容；验签或解密失败返回 null
     */
    WxpayTransferNotificationResult decryptTransferNotification(WxpayConfig config, Map<String, String> headers, String rawBody);
}
