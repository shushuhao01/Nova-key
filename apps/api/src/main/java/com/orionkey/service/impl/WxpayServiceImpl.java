package com.orionkey.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.constant.ErrorCode;
import com.orionkey.dto.PaymentTestResult;
import com.orionkey.exception.BusinessException;
import com.orionkey.service.WxpayService;
import com.orionkey.utils.PaymentCryptoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原生微信支付 APIv3 实现。
 * - Native 扫码下单：POST /v3/pay/transactions/native
 * - 主动查单：GET /v3/pay/transactions/out-trade-no/{out_trade_no}
 * - 回调验签：拉取平台证书（/v3/certificates，加密传输）校验 WECHATPAY2 签名，再用 APIv3 密钥解密资源
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WxpayServiceImpl implements WxpayService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String DEFAULT_GATEWAY = "https://api.mch.weixin.qq.com";

    /** 平台证书缓存有效期（12 小时） */
    private static final long CERT_CACHE_TTL_MS = 12 * 60 * 60 * 1000L;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /** 平台证书缓存：serial_no → {证书, 加载时间} */
    private final Map<String, CertEntry> platformCertCache = new ConcurrentHashMap<>();

    private record CertEntry(X509Certificate cert, long loadedAt) {
    }

    @Override
    public WxpayPaymentResult createNativePayment(WxpayConfig config, String outTradeNo, String description,
                                                  BigDecimal amount, String clientIp) {
        String canonicalPath = "/v3/pay/transactions/native";
        String gateway = trimSlash(config.gatewayUrl());

        int totalCents = amount.multiply(HUNDRED).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        if (totalCents <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "微信支付金额必须大于 0");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", config.appid());
        body.put("mchid", config.mchid());
        body.put("description", description);
        body.put("out_trade_no", outTradeNo);
        body.put("notify_url", config.notifyUrl());
        Map<String, Object> amountMap = new LinkedHashMap<>();
        amountMap.put("total", totalCents);
        amountMap.put("currency", "CNY");
        body.put("amount", amountMap);
        if (clientIp != null && !clientIp.isBlank()) {
            body.put("scene_info", Map.of("payer_client_ip", clientIp));
        }

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            ResponseEntity<String> response = restTemplate.exchange(
                    gateway + canonicalPath, HttpMethod.POST,
                    new HttpEntity<>(jsonBody, apiV3Headers(config, "POST", canonicalPath, jsonBody)),
                    String.class);
            Map<String, Object> resp = objectMapper.readValue(response.getBody(), new TypeReference<>() {
            });
            String codeUrl = resp.get("code_url") != null ? resp.get("code_url").toString() : null;
            if (codeUrl == null || codeUrl.isBlank()) {
                log.error("Wxpay native order failed: outTradeNo={}, resp={}", outTradeNo, response.getBody());
                throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "微信支付下单失败：" + response.getBody());
            }
            log.info("Wxpay native order created: outTradeNo={}, totalCents={}", outTradeNo, totalCents);
            return new WxpayPaymentResult(codeUrl);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Wxpay native order API error: outTradeNo={}, error={}", outTradeNo, e.getMessage());
            throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "微信支付下单失败：网络错误");
        }
    }

    @Override
    public WxpayPaymentResult createH5Payment(WxpayConfig config, String outTradeNo, String description,
                                               BigDecimal amount, String clientIp) {
        String canonicalPath = "/v3/pay/transactions/h5";
        String gateway = trimSlash(config.gatewayUrl());

        int totalCents = amount.multiply(HUNDRED).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        if (totalCents <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "微信支付金额必须大于 0");
        }

        String ip = (clientIp != null && !clientIp.isBlank()) ? clientIp : "127.0.0.1";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", config.appid());
        body.put("mchid", config.mchid());
        body.put("description", description);
        body.put("out_trade_no", outTradeNo);
        body.put("notify_url", config.notifyUrl());
        Map<String, Object> amountMap = new LinkedHashMap<>();
        amountMap.put("total", totalCents);
        amountMap.put("currency", "CNY");
        body.put("amount", amountMap);
        // H5 支付必须提供 scene_info
        Map<String, Object> sceneInfo = new LinkedHashMap<>();
        sceneInfo.put("payer_client_ip", ip);
        sceneInfo.put("h5_info", Map.of("type", "Wap"));
        body.put("scene_info", sceneInfo);

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            ResponseEntity<String> response = restTemplate.exchange(
                    gateway + canonicalPath, HttpMethod.POST,
                    new HttpEntity<>(jsonBody, apiV3Headers(config, "POST", canonicalPath, jsonBody)),
                    String.class);
            Map<String, Object> resp = objectMapper.readValue(response.getBody(), new TypeReference<>() {
            });
            String h5Url = resp.get("h5_url") != null ? resp.get("h5_url").toString() : null;
            if (h5Url == null || h5Url.isBlank()) {
                log.error("Wxpay H5 order failed: outTradeNo={}, resp={}", outTradeNo, response.getBody());
                throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "微信H5支付下单失败：" + response.getBody());
            }
            log.info("Wxpay H5 order created: outTradeNo={}, totalCents={}", outTradeNo, totalCents);
            return WxpayPaymentResult.h5(h5Url);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Wxpay H5 order API error: outTradeNo={}, error={}", outTradeNo, e.getMessage());
            throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "微信H5支付下单失败：网络错误");
        }
    }

    @Override
    public WxpayOrderQueryResult queryOrder(WxpayConfig config, String outTradeNo) {
        String canonicalPath = "/v3/pay/transactions/out-trade-no/" + outTradeNo + "?mchid=" + config.mchid();
        String gateway = trimSlash(config.gatewayUrl());
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    gateway + canonicalPath, HttpMethod.GET,
                    new HttpEntity<>(apiV3Headers(config, "GET", canonicalPath, "")),
                    String.class);
            Map<String, Object> resp = objectMapper.readValue(response.getBody(), new TypeReference<>() {
            });
            String tradeState = resp.get("trade_state") != null ? resp.get("trade_state").toString() : null;
            String transactionId = resp.get("transaction_id") != null ? resp.get("transaction_id").toString() : null;
            Integer total = null;
            if (resp.get("amount") instanceof Map<?, ?> am && am.get("total") instanceof Number n) {
                total = n.intValue();
            }
            return new WxpayOrderQueryResult(tradeState, total, transactionId);
        } catch (Exception e) {
            log.warn("Wxpay order query failed: outTradeNo={}, error={}", outTradeNo, e.getMessage());
            return null;
        }
    }

    @Override
    public WxpayNotificationResult decryptNotification(WxpayConfig config, Map<String, String> headers, String rawBody) {
        try {
            Map<String, Object> resource = verifyAndDecryptResource(config, headers, rawBody);
            if (resource == null) {
                return null;
            }
            Map<String, Object> root = objectMapper.readValue(rawBody, new TypeReference<>() {
            });
            String id = root.get("id") != null ? root.get("id").toString() : null;
            String eventType = root.get("event_type") != null ? root.get("event_type").toString() : null;
            String outTradeNo = resource.get("out_trade_no") != null ? resource.get("out_trade_no").toString() : null;
            String transactionId = resource.get("transaction_id") != null ? resource.get("transaction_id").toString() : null;
            String tradeState = resource.get("trade_state") != null ? resource.get("trade_state").toString() : null;
            Integer total = null;
            if (resource.get("amount") instanceof Map<?, ?> am && am.get("total") instanceof Number n) {
                total = n.intValue();
            }
            return new WxpayNotificationResult(id, eventType, outTradeNo, tradeState, total, transactionId);
        } catch (Exception e) {
            log.warn("Wxpay notification processing failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public WxpayTransferNotificationResult decryptTransferNotification(WxpayConfig config, Map<String, String> headers, String rawBody) {
        try {
            Map<String, Object> resource = verifyAndDecryptResource(config, headers, rawBody);
            if (resource == null) {
                return null;
            }
            Map<String, Object> root = objectMapper.readValue(rawBody, new TypeReference<>() {
            });
            String id = root.get("id") != null ? root.get("id").toString() : null;
            String outBillNo = resource.get("out_bill_no") != null ? resource.get("out_bill_no").toString() : null;
            String state = resource.get("state") != null ? resource.get("state").toString() : null;
            String failReason = resource.get("fail_reason") != null ? resource.get("fail_reason").toString() : null;
            return new WxpayTransferNotificationResult(id, outBillNo, state, failReason);
        } catch (Exception e) {
            log.warn("Wxpay transfer notification processing failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 微信 APIv3 通知通用处理：平台证书验签 + APIv3 密钥 AES-256-GCM 解密资源内容。
     *
     * @return 解密后的资源内容（Map）；验签/解密失败或头部缺失返回 null
     */
    private Map<String, Object> verifyAndDecryptResource(WxpayConfig config, Map<String, String> headers, String rawBody) throws Exception {
        String timestamp = headers.get("wechatpay-timestamp");
        String nonce = headers.get("wechatpay-nonce");
        String signature = headers.get("wechatpay-signature");
        String serial = headers.get("wechatpay-serial");
        if (timestamp == null || nonce == null || signature == null || serial == null) {
            log.warn("Wxpay notification missing signature headers");
            return null;
        }

        // 1. 平台证书验签
        X509Certificate platformCert = getPlatformCertificate(config, serial);
        if (platformCert == null) {
            return null;
        }
        String message = timestamp + "\n" + nonce + "\n" + rawBody + "\n";
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(platformCert.getPublicKey());
        sig.update(message.getBytes(StandardCharsets.UTF_8));
        if (!sig.verify(Base64.getDecoder().decode(signature))) {
            log.warn("Wxpay notification signature verification failed, serial={}", serial);
            return null;
        }

        // 2. 解密资源内容
        Map<String, Object> root = objectMapper.readValue(rawBody, new TypeReference<>() {
        });
        if (!(root.get("resource") instanceof Map<?, ?> resource)) {
            return null;
        }
        String algorithm = resource.get("algorithm") != null ? resource.get("algorithm").toString() : null;
        String resNonce = resource.get("nonce") != null ? resource.get("nonce").toString() : null;
        String associatedData = resource.get("associated_data") != null ? resource.get("associated_data").toString() : null;
        String ciphertext = resource.get("ciphertext") != null ? resource.get("ciphertext").toString() : null;
        if (!"AEAD_AES_256_GCM".equals(algorithm) || resNonce == null || ciphertext == null) {
            log.warn("Wxpay notification unsupported resource format");
            return null;
        }
        String decrypted = PaymentCryptoUtils.decryptAesGcm(config.apiV3Key(), resNonce, associatedData, ciphertext);
        return objectMapper.readValue(decrypted, new TypeReference<>() {
        });
    }

    @Override
    public PaymentTestResult testConnection(WxpayConfig config) {
        // 逐项检测清单，单项失败不抛异常，通过 items.status 体现（前端渲染 ✅/❌）
        List<PaymentTestResult.TestItem> items = new java.util.ArrayList<>();

        // 1. AppID
        boolean hasAppId = isNotBlank(config.appid());
        items.add(new PaymentTestResult.TestItem("AppID", hasAppId,
                hasAppId ? "AppID已配置: " + config.appid() : "未配置AppID"));

        // 2. 商户号
        boolean hasMchId = isNotBlank(config.mchid());
        items.add(new PaymentTestResult.TestItem("商户号", hasMchId,
                hasMchId ? "商户号已配置: " + config.mchid() : "未配置商户号"));

        // 3. API密钥（APIv3 密钥，回调解密与下单都要用）
        boolean hasApiKey = isNotBlank(config.apiV3Key());
        items.add(new PaymentTestResult.TestItem("API密钥", hasApiKey,
                hasApiKey ? "API密钥已配置" : "未配置API密钥（APIv3）"));

        // 4. 证书（证书序列号 + 商户私钥必须成对；商家支付证书 apiclient_cert.pem 为可选留存）
        boolean hasSerial = isNotBlank(config.serialNo());
        boolean hasPrivateKey = isNotBlank(config.privateKey());
        boolean certOk = hasSerial && hasPrivateKey;
        String certMsg;
        if (certOk) {
            certMsg = "证书信息已配置（序列号 " + config.serialNo() + " + 商户私钥）";
        } else {
            StringBuilder sb = new StringBuilder("证书信息不完整：");
            if (!hasSerial) sb.append("缺少证书序列号(serial_no)；");
            if (!hasPrivateKey) sb.append("缺少商户私钥(apiclient_key.pem 内容或路径)；");
            certMsg = sb.toString();
        }
        items.add(new PaymentTestResult.TestItem("证书", certOk, certMsg));

        // 5. 连接测试：证书序列号+私钥齐全且商户号、API密钥齐全时，真实调用 /v3/certificates 验证签名
        boolean connOk = false;
        String connMsg;
        if (!hasMchId || !hasApiKey || !certOk) {
            connMsg = "配置不完整（商户号/API密钥/证书缺一不可），无法进行真实连接测试";
        } else {
            try {
                // 提前验证商户私钥可解析（最常见的配置错误：私钥为空 / 格式不对 / PEM 头缺失）
                PaymentCryptoUtils.parsePrivateKey(config.privateKey());
                // 调用微信支付 APIv3 获取平台证书，验证 商户号+证书序列号+私钥签名 是否有效
                String canonicalPath = "/v3/certificates";
                String gateway = trimSlash(config.gatewayUrl());
                ResponseEntity<String> response = restTemplate.exchange(
                        gateway + canonicalPath, HttpMethod.GET,
                        new HttpEntity<>(apiV3Headers(config, "GET", canonicalPath, "")),
                        String.class);
                Map<String, Object> resp = objectMapper.readValue(response.getBody(), new TypeReference<>() {
                });
                int count = resp.get("data") instanceof List<?> list ? list.size() : 0;
                connOk = true;
                connMsg = "微信支付V3接口连接成功，平台证书获取正常（共 " + count + " 张）";
            } catch (HttpClientErrorException e) {
                String detail = extractWxErrorDetail(e);
                connMsg = "微信支付认证失败（HTTP " + e.getStatusCode().value() + "）：" + detail
                        + "。请核对商户号(mchid)、证书序列号(serial_no)、商户私钥是否一致，且商户平台已配置 APIv3 密钥";
            } catch (HttpServerErrorException e) {
                connMsg = "微信支付服务异常（HTTP " + e.getStatusCode().value() + "）：" + e.getResponseBodyAsString();
            } catch (RestClientException e) {
                connMsg = "无法连接微信支付服务器：" + e.getMessage()
                        + "。请检查服务器网络能否访问 api.mch.weixin.qq.com";
            } catch (IllegalArgumentException e) {
                connMsg = "商户私钥解析失败：" + e.getMessage()
                        + "。请检查 apiclient_key.pem 内容是否正确完整（含 -----BEGIN ...----- 头）";
            } catch (Exception e) {
                connMsg = "微信支付连接测试异常：" + e.getMessage();
            }
        }
        items.add(new PaymentTestResult.TestItem("连接测试", connOk, connMsg));

        boolean passed = items.stream().allMatch(i -> i.status());
        String summary = passed ? "微信支付配置验证通过" : "部分配置项未通过验证，请根据上方 ❌ 项修正";
        return new PaymentTestResult(passed, items, summary);
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    @Override
    public WxpayRefundResult createRefund(WxpayConfig config, String outTradeNo, String outRefundNo,
                                          BigDecimal refundAmount, BigDecimal totalAmount,
                                          String reason, String notifyUrl) {
        String canonicalPath = "/v3/refund/domestic/refunds";
        String gateway = trimSlash(config.gatewayUrl());

        int refundCents = refundAmount.multiply(HUNDRED).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        int totalCents = totalAmount.multiply(HUNDRED).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        if (refundCents <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "退款金额必须大于 0");
        }
        if (refundCents > totalCents) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "退款金额不能超过订单实付金额");
        }
        if (outTradeNo == null || outTradeNo.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "原商户订单号不能为空");
        }
        if (outRefundNo == null || outRefundNo.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "商户退款单号不能为空");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("out_trade_no", outTradeNo);
        body.put("out_refund_no", outRefundNo);
        if (reason != null && !reason.isBlank()) {
            body.put("reason", reason.length() > 80 ? reason.substring(0, 80) : reason);
        }
        if (notifyUrl != null && !notifyUrl.isBlank()) {
            body.put("notify_url", notifyUrl);
        }
        Map<String, Object> amountMap = new LinkedHashMap<>();
        amountMap.put("refund", refundCents);
        amountMap.put("total", totalCents);
        amountMap.put("currency", "CNY");
        body.put("amount", amountMap);

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            ResponseEntity<String> response = restTemplate.exchange(
                    gateway + canonicalPath, HttpMethod.POST,
                    new HttpEntity<>(jsonBody, apiV3Headers(config, "POST", canonicalPath, jsonBody)),
                    String.class);
            Map<String, Object> resp = objectMapper.readValue(response.getBody(), new TypeReference<>() {
            });
            String refundId = resp.get("refund_id") != null ? resp.get("refund_id").toString() : null;
            String refundNo = resp.get("out_refund_no") != null ? resp.get("out_refund_no").toString() : null;
            String status = resp.get("status") != null ? resp.get("status").toString() : null;
            if (refundId == null && refundNo == null) {
                log.error("Wxpay refund failed: outTradeNo={}, outRefundNo={}, resp={}", outTradeNo, outRefundNo, response.getBody());
                throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "微信退款失败：" + response.getBody());
            }
            // 微信退款受理但已关闭/异常（余额不足等）时直接报错，避免本地误标退款成功
            if ("CLOSED".equals(status) || "ABNORMAL".equals(status)) {
                log.error("Wxpay refund rejected: outTradeNo={}, status={}, resp={}", outTradeNo, status, response.getBody());
                throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "微信退款未受理（" + status + "）：" + response.getBody());
            }
            log.info("Wxpay refund created: outTradeNo={}, outRefundNo={}, status={}, refundId={}",
                    outTradeNo, refundNo, status, refundId);
            return new WxpayRefundResult(refundId, refundNo, status);
        } catch (BusinessException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            String detail = extractWxErrorDetail(e);
            log.error("Wxpay refund API error: outTradeNo={}, status={}, detail={}", outTradeNo, e.getStatusCode().value(), detail);
            throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "微信退款失败：" + detail);
        } catch (Exception e) {
            log.error("Wxpay refund API error: outTradeNo={}, error={}", outTradeNo, e.getMessage());
            throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "微信退款失败：网络错误");
        }
    }

    @Override
    public WxpayTransferResult createTransfer(WxpayConfig config, String outBillNo, String openid,
                                              BigDecimal amount, String remark, String notifyUrl) {
        // 商家转账单笔接口（场景ID/感知文案等字段属此接口）：POST /v3/fund-app/mch-transfer/transfer-bills
        String canonicalPath = "/v3/fund-app/mch-transfer/transfer-bills";
        String gateway = trimSlash(config.gatewayUrl());

        int totalCents = amount.multiply(HUNDRED).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        if (totalCents <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "转账金额必须大于 0");
        }
        if (openid == null || openid.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "收款人 openid 不能为空");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", config.appid());
        body.put("out_bill_no", outBillNo);
        // 转账场景ID：佣金报酬默认 1005，可从渠道配置读取
        String sceneId = isNotBlank(config.transferSceneId()) ? config.transferSceneId() : "1005";
        body.put("transfer_scene_id", sceneId);
        // 转账场景报备信息：佣金报酬(1005)场景微信要求必填「岗位类型 + 报酬说明」，用于报备转账资金用途
        // （info_type 为微信固定值，info_content 商户自定义，可从渠道配置覆盖）
        if ("1005".equals(sceneId)) {
            List<Map<String, String>> reportInfos = new ArrayList<>();
            reportInfos.add(transferSceneReportItem("岗位类型",
                    isNotBlank(config.transferSceneJobType()) ? config.transferSceneJobType() : "销售顾问"));
            reportInfos.add(transferSceneReportItem("报酬说明",
                    isNotBlank(config.transferSceneRemark()) ? config.transferSceneRemark() : "佣金报酬结算"));
            body.put("transfer_scene_report_infos", reportInfos);
        }
        body.put("openid", openid);
        body.put("transfer_amount", totalCents);
        body.put("transfer_remark", remark != null && !remark.isBlank() ? remark : "佣金提现");
        body.put("notify_url", notifyUrl);

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            ResponseEntity<String> response = restTemplate.exchange(
                    gateway + canonicalPath, HttpMethod.POST,
                    new HttpEntity<>(jsonBody, apiV3Headers(config, "POST", canonicalPath, jsonBody)),
                    String.class);
            Map<String, Object> resp = objectMapper.readValue(response.getBody(), new TypeReference<>() {
            });
            String transferBillNo = resp.get("transfer_bill_no") != null ? resp.get("transfer_bill_no").toString() : null;
            String state = resp.get("state") != null ? resp.get("state").toString() : null;
            String packageInfo = resp.get("package_info") != null ? resp.get("package_info").toString() : null;
            if (transferBillNo == null && packageInfo == null) {
                log.error("Wxpay transfer failed: outBillNo={}, resp={}", outBillNo, response.getBody());
                throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "微信转账失败：" + response.getBody());
            }
            log.info("Wxpay transfer created: outBillNo={}, state={}, transferBillNo={}", outBillNo, state, transferBillNo);
            return new WxpayTransferResult(outBillNo, transferBillNo, state, packageInfo);
        } catch (BusinessException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            String detail = extractWxErrorDetail(e);
            log.error("Wxpay transfer API error: outBillNo={}, status={}, detail={}", outBillNo, e.getStatusCode().value(), detail);
            throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "微信转账失败：" + detail);
        } catch (Exception e) {
            log.error("Wxpay transfer API error: outBillNo={}, error={}", outBillNo, e.getMessage());
            throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "微信转账失败：网络错误");
        }
    }

    /**
     * 转账场景报备信息明细项（transfer_scene_report_infos 数组元素）。
     * info_type 为微信固定的中文取值，info_content 为商户自定义内容（长度 1~32）。
     */
    private Map<String, String> transferSceneReportItem(String infoType, String infoContent) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("info_type", infoType);
        item.put("info_content", infoContent);
        return item;
    }

    @Override
    public WxpayTransferQueryResult queryTransfer(WxpayConfig config, String outBillNo) {
        // 查询商家转账单笔状态：GET /v3/fund-app/mch-transfer/transfer-bills/out-bill-no/{out_bill_no}（普通商户版，无需 mchid 参数）
        String canonicalPath = "/v3/fund-app/mch-transfer/transfer-bills/out-bill-no/" + outBillNo;
        String gateway = trimSlash(config.gatewayUrl());
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    gateway + canonicalPath, HttpMethod.GET,
                    new HttpEntity<>(apiV3Headers(config, "GET", canonicalPath, "")),
                    String.class);
            Map<String, Object> resp = objectMapper.readValue(response.getBody(), new TypeReference<>() {
            });
            String state = resp.get("state") != null ? resp.get("state").toString() : null;
            String transferBillNo = resp.get("transfer_bill_no") != null ? resp.get("transfer_bill_no").toString() : null;
            String failReason = resp.get("fail_reason") != null ? resp.get("fail_reason").toString() : null;
            Integer transferAmount = null;
            if (resp.get("transfer_amount") instanceof Number n) {
                transferAmount = n.intValue();
            }
            return new WxpayTransferQueryResult(state, transferBillNo, failReason, transferAmount, null);
        } catch (Exception e) {
            String detail = e instanceof org.springframework.web.client.HttpStatusCodeException hce
                    ? hce.getResponseBodyAsString() : e.getMessage();
            if (detail != null && detail.length() > 300) detail = detail.substring(0, 300);
            log.warn("Wxpay transfer query failed: outBillNo={}, error={}", outBillNo, detail);
            return new WxpayTransferQueryResult(null, null, null, null, detail);
        }
    }

    /** 从微信 APIv3 错误响应体中提取 message 字段（如签名错误的具体原因） */
    private String extractWxErrorDetail(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) return e.getMessage();
        try {
            Map<String, Object> m = objectMapper.readValue(body, new TypeReference<>() {
            });
            Object message = m.get("message");
            if (message != null && !message.toString().isBlank()) return message.toString();
        } catch (Exception ignored) {
            // fall through
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    /**
     * 获取平台证书（带缓存），用于回调验签。
     */
    private X509Certificate getPlatformCertificate(WxpayConfig config, String serialNo) {
        CertEntry cached = platformCertCache.get(serialNo);
        if (cached != null && System.currentTimeMillis() - cached.loadedAt() < CERT_CACHE_TTL_MS) {
            return cached.cert();
        }
        Map<String, X509Certificate> certs = fetchPlatformCertificates(config);
        long now = System.currentTimeMillis();
        for (Map.Entry<String, X509Certificate> entry : certs.entrySet()) {
            platformCertCache.put(entry.getKey(), new CertEntry(entry.getValue(), now));
        }
        return certs.get(serialNo);
    }

    /**
     * 拉取微信支付平台证书（响应中证书用 APIv3 密钥加密）。
     */
    private Map<String, X509Certificate> fetchPlatformCertificates(WxpayConfig config) {
        String canonicalPath = "/v3/certificates";
        String gateway = trimSlash(config.gatewayUrl());
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    gateway + canonicalPath, HttpMethod.GET,
                    new HttpEntity<>(apiV3Headers(config, "GET", canonicalPath, "")),
                    String.class);
            Map<String, Object> resp = objectMapper.readValue(response.getBody(), new TypeReference<>() {
            });
            Map<String, X509Certificate> result = new LinkedHashMap<>();
            if (resp.get("data") instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> certMap)) continue;
                    String serialNo = certMap.get("serial_no") != null ? certMap.get("serial_no").toString() : null;
                    if (!(certMap.get("encrypt_certificate") instanceof Map<?, ?> enc)) continue;
                    String nonce = enc.get("nonce") != null ? enc.get("nonce").toString() : null;
                    String associatedData = enc.get("associated_data") != null ? enc.get("associated_data").toString() : null;
                    String ciphertext = enc.get("ciphertext") != null ? enc.get("ciphertext").toString() : null;
                    if (serialNo == null || nonce == null || ciphertext == null) continue;
                    String pem = PaymentCryptoUtils.decryptAesGcm(config.apiV3Key(), nonce, associatedData, ciphertext);
                    result.put(serialNo, parseX509Pem(pem));
                }
            }
            if (result.isEmpty()) {
                log.error("Wxpay platform certificates fetch returned empty, resp={}", response.getBody());
            }
            return result;
        } catch (Exception e) {
            log.error("Wxpay platform certificates fetch failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private X509Certificate parseX509Pem(String pem) throws Exception {
        byte[] der = Base64.getDecoder().decode(
                pem.replace("-----BEGIN CERTIFICATE-----", "")
                        .replace("-----END CERTIFICATE-----", "")
                        .replaceAll("\\s", ""));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
    }

    /**
     * 构建 APIv3 请求头（含商户签名）。
     * 签名消息格式：{method}\n{canonicalUrl}\n{timestamp}\n{nonce}\n{body}\n
     */
    private HttpHeaders apiV3Headers(WxpayConfig config, String method, String canonicalUrl, String body) {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        long timestamp = System.currentTimeMillis() / 1000;
        String message = method + "\n" + canonicalUrl + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        String signature = PaymentCryptoUtils.sign(
                PaymentCryptoUtils.parsePrivateKey(config.privateKey()), message);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String auth = "WECHATPAY2-SHA256-RSA2048 mchid=\"" + config.mchid()
                + "\",nonce_str=\"" + nonce
                + "\",signature=\"" + signature
                + "\",timestamp=\"" + timestamp
                + "\",serial_no=\"" + config.serialNo() + "\"";
        headers.set("Authorization", auth);
        return headers;
    }

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) return DEFAULT_GATEWAY;
        return url.replaceAll("/+$", "");
    }
}
