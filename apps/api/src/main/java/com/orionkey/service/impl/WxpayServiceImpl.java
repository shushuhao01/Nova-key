package com.orionkey.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.constant.ErrorCode;
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
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
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
            String id = root.get("id") != null ? root.get("id").toString() : null;
            String eventType = root.get("event_type") != null ? root.get("event_type").toString() : null;
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
            Map<String, Object> pay = objectMapper.readValue(decrypted, new TypeReference<>() {
            });
            String outTradeNo = pay.get("out_trade_no") != null ? pay.get("out_trade_no").toString() : null;
            String transactionId = pay.get("transaction_id") != null ? pay.get("transaction_id").toString() : null;
            String tradeState = pay.get("trade_state") != null ? pay.get("trade_state").toString() : null;
            Integer total = null;
            if (pay.get("amount") instanceof Map<?, ?> am && am.get("total") instanceof Number n) {
                total = n.intValue();
            }
            return new WxpayNotificationResult(id, eventType, outTradeNo, tradeState, total, transactionId);
        } catch (Exception e) {
            log.warn("Wxpay notification processing failed: {}", e.getMessage());
            return null;
        }
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
