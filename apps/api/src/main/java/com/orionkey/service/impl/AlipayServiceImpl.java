package com.orionkey.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.constant.ErrorCode;
import com.orionkey.dto.PaymentTestResult;
import com.orionkey.exception.BusinessException;
import com.orionkey.service.AlipayService;
import com.orionkey.utils.PaymentCryptoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 原生支付宝实现（RSA2 签名）。
 * - 当面付预下单：alipay.trade.precreate（PC 扫码）
 * - 手机网站支付：alipay.trade.wap.pay（移动端 H5 跳转）
 * - 主动查单：alipay.trade.query
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlipayServiceImpl implements AlipayService {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public AlipayPaymentResult createPrecreate(AlipayConfig config, String outTradeNo, String subject, BigDecimal amount) {
        String bizContent = buildBizContent(outTradeNo, subject, amount, "alipay.trade.precreate");
        Map<String, String> params = baseParams(config, "alipay.trade.precreate", bizContent);
        String sign = sign(params, config.privateKey());
        params.put("sign", sign);

        try {
            String respBody = postForm(config.gatewayUrl(), params);
            Map<String, Object> resp = objectMapper.readValue(respBody, new TypeReference<>() {
            });
            Map<String, Object> apiResp = readApiResponse(resp, "alipay_trade_precreate_response");
            String code = apiResp.get("code") != null ? apiResp.get("code").toString() : null;
            if (!"10000".equals(code)) {
                String subMsg = apiResp.get("sub_msg") != null ? apiResp.get("sub_msg").toString() : null;
                log.error("Alipay precreate failed: code={}, msg={}, subMsg={}, outTradeNo={}",
                        code, apiResp.get("msg"), subMsg, outTradeNo);
                throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL,
                        "支付宝下单失败：" + (subMsg != null ? subMsg : apiResp.get("msg")));
            }
            String qrCode = apiResp.get("qr_code") != null ? apiResp.get("qr_code").toString() : null;
            if (qrCode == null || qrCode.isBlank()) {
                log.error("Alipay precreate missing qr_code: outTradeNo={}, resp={}", outTradeNo, respBody);
                throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "支付宝下单失败：未返回二维码");
            }
            log.info("Alipay precreate ok: outTradeNo={}", outTradeNo);
            return new AlipayPaymentResult(qrCode);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Alipay precreate API error: outTradeNo={}, error={}", outTradeNo, e.getMessage());
            throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "支付宝下单失败：网络错误");
        }
    }

    @Override
    public String buildWapPayUrl(AlipayConfig config, String outTradeNo, String subject, BigDecimal amount) {
        String bizContent = buildBizContent(outTradeNo, subject, amount, "alipay.trade.wap.pay");
        Map<String, String> params = baseParams(config, "alipay.trade.wap.pay", bizContent);
        String sign = sign(params, config.privateKey());
        params.put("sign", sign);

        StringBuilder url = new StringBuilder(config.gatewayUrl());
        url.append(config.gatewayUrl().contains("?") ? "&" : "?");
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) url.append('&');
            first = false;
            url.append(entry.getKey()).append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        log.info("Alipay wap pay url built: outTradeNo={}", outTradeNo);
        return url.toString();
    }

    @Override
    public AlipayOrderQueryResult queryOrder(AlipayConfig config, String outTradeNo) {
        String bizContent = "{\"out_trade_no\":\"" + outTradeNo + "\"}";
        Map<String, String> params = baseParams(config, "alipay.trade.query", bizContent);
        String sign = sign(params, config.privateKey());
        params.put("sign", sign);
        try {
            String respBody = postForm(config.gatewayUrl(), params);
            Map<String, Object> resp = objectMapper.readValue(respBody, new TypeReference<>() {
            });
            Map<String, Object> apiResp = readApiResponse(resp, "alipay_trade_query_response");
            String code = apiResp.get("code") != null ? apiResp.get("code").toString() : null;
            if (!"10000".equals(code)) {
                log.warn("Alipay query failed: code={}, sub_msg={}, outTradeNo={}",
                        code, apiResp.get("sub_msg"), outTradeNo);
                return null;
            }
            String tradeStatus = apiResp.get("trade_status") != null ? apiResp.get("trade_status").toString() : null;
            String totalAmount = apiResp.get("total_amount") != null ? apiResp.get("total_amount").toString() : null;
            String tradeNo = apiResp.get("trade_no") != null ? apiResp.get("trade_no").toString() : null;
            return new AlipayOrderQueryResult(tradeStatus, totalAmount, tradeNo);
        } catch (Exception e) {
            log.warn("Alipay order query failed: outTradeNo={}, error={}", outTradeNo, e.getMessage());
            return null;
        }
    }

    @Override
    public PaymentTestResult testConnection(AlipayConfig config) {
        // 逐项检测清单，单项失败不抛异常，通过 items.status 体现（前端渲染 ✅/❌）
        List<PaymentTestResult.TestItem> items = new java.util.ArrayList<>();

        // 1. AppID
        boolean hasAppId = isNotBlank(config.appId());
        items.add(new PaymentTestResult.TestItem("AppID", hasAppId,
                hasAppId ? "AppID已配置: " + config.appId() : "未配置支付应用Appid"));

        // 2. 商家私钥（应用私钥，用于签名请求）
        boolean hasPrivateKey = isNotBlank(config.privateKey());
        items.add(new PaymentTestResult.TestItem("商家私钥", hasPrivateKey,
                hasPrivateKey ? "商家私钥已配置" : "未配置商家私钥（应用私钥）"));

        // 3. 支付宝公钥（用于验证回调/响应签名）
        boolean hasPublicKey = isNotBlank(config.alipayPublicKey());
        items.add(new PaymentTestResult.TestItem("支付宝公钥", hasPublicKey,
                hasPublicKey ? "支付宝公钥已配置" : "未配置支付宝公钥"));

        // 4. 签名类型（支付宝目前仅支持 RSA2，默认值即 RSA2）
        String signType = isNotBlank(config.signType()) ? config.signType() : "RSA2";
        items.add(new PaymentTestResult.TestItem("签名类型", true, "签名类型: " + signType));

        // 5. 连接测试：AppID + 商家私钥齐全时，用一笔不存在的订单号调用查询接口，
        //    验证 签名 与 响应验签（支付宝公钥）是否有效
        boolean connOk = false;
        String connMsg;
        if (!hasAppId || !hasPrivateKey) {
            connMsg = "配置不完整（缺少AppID或商家私钥），无法进行真实连接测试";
        } else {
            String outTradeNo = "NOVA_TEST_" + System.currentTimeMillis();
            String bizContent = "{\"out_trade_no\":\"" + outTradeNo + "\"}";
            Map<String, String> params = baseParams(config, "alipay.trade.query", bizContent);
            try {
                String sign = sign(params, config.privateKey());
                params.put("sign", sign);
                String respBody = postForm(config.gatewayUrl(), params);
                Map<String, Object> resp = objectMapper.readValue(respBody, new TypeReference<>() {
                });
                Map<String, Object> apiResp = readApiResponse(resp, "alipay_trade_query_response");

                // 先验证响应签名：能验签通过说明支付宝公钥正确
                if (resp.get("sign") instanceof String respSign && !respSign.isBlank()) {
                    Map<String, String> verifyParams = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : apiResp.entrySet()) {
                        verifyParams.put(entry.getKey(), String.valueOf(entry.getValue()));
                    }
                    if (!verifySign(config.alipayPublicKey(), verifyParams, respSign)) {
                        throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,
                                "支付宝响应验签失败：支付宝公钥可能不正确，请在支付宝开放平台「密钥管理」中核对公钥（应填支付宝公钥，而非应用公钥）");
                    }
                }

                String code = apiResp.get("code") != null ? apiResp.get("code").toString() : null;
                String subMsg = apiResp.get("sub_msg") != null ? apiResp.get("sub_msg").toString() : null;
                // code=10000 成功；code=40004(ACQ.TRADE_NOT_EXIST) 表示签名验证通过、仅订单不存在（测试单号必然不存在）
                if ("10000".equals(code) || "40004".equals(code)) {
                    connOk = true;
                    connMsg = "支付宝网关连接成功，AppID 与商家私钥、支付宝公钥配置正确，签名验证通过";
                } else {
                    connMsg = "支付宝接口返回错误：code=" + code + "（msg=" + apiResp.get("msg")
                            + (subMsg != null ? "，sub_msg=" + subMsg : "") + "）";
                }
            } catch (BusinessException e) {
                connMsg = e.getMessage();
            } catch (IllegalArgumentException e) {
                connMsg = "商家私钥解析失败：" + e.getMessage()
                        + "。请检查私钥内容是否正确完整（含 -----BEGIN ...----- 头）";
            } catch (RestClientException e) {
                connMsg = "无法连接支付宝服务器：" + e.getMessage()
                        + "。请检查服务器网络能否访问 openapi.alipay.com";
            } catch (Exception e) {
                connMsg = "支付宝连接测试异常：" + e.getMessage();
            }
        }
        items.add(new PaymentTestResult.TestItem("连接测试", connOk, connMsg));

        boolean passed = items.stream().allMatch(i -> i.status());
        String summary = passed ? "支付宝配置验证通过" : "部分配置项未通过验证，请根据上方 ❌ 项修正";
        return new PaymentTestResult(passed, items, summary);
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    @Override
    public boolean verifySign(String alipayPublicKey, Map<String, String> params, String sign) {
        if (sign == null || sign.isBlank()) return false;
        try {
            String content = buildSignContent(params);
            return PaymentCryptoUtils.verify(
                    PaymentCryptoUtils.parsePublicKey(alipayPublicKey), content, sign);
        } catch (Exception e) {
            log.warn("Alipay sign verification error: {}", e.getMessage());
            return false;
        }
    }

    // ── 内部工具 ──

    /**
     * 构建 biz_content。wap.pay 需要 product_code=QUICK_WAP_WAY，precreate 不需要。
     */
    private String buildBizContent(String outTradeNo, String subject, BigDecimal amount, String method) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"out_trade_no\":\"").append(outTradeNo).append("\",");
        sb.append("\"total_amount\":\"").append(amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()).append("\",");
        sb.append("\"subject\":\"").append(escapeJson(subject)).append("\"");
        if ("alipay.trade.wap.pay".equals(method)) {
            sb.append(",\"product_code\":\"QUICK_WAP_WAY\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private Map<String, String> baseParams(AlipayConfig config, String method, String bizContent) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", config.appId());
        params.put("method", method);
        params.put("format", "JSON");
        params.put("charset", "utf-8");
        // 签名类型：优先渠道配置，默认 RSA2（支付宝目前仅支持 RSA2）
        String signType = config.signType() != null && !config.signType().isBlank()
                ? config.signType() : "RSA2";
        params.put("sign_type", signType);
        // 支付宝要求 timestamp 为商户本地时间（北京时间），固定时区格式化，
        // 避免服务器 JVM 时区非 Asia/Shanghai 时签名时间戳偏差被支付宝拒绝
        params.put("timestamp", LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(TIMESTAMP_FORMAT));
        params.put("version", "1.0");
        if (config.notifyUrl() != null && !config.notifyUrl().isBlank()) {
            params.put("notify_url", config.notifyUrl());
        }
        params.put("biz_content", bizContent);
        return params;
    }

    /**
     * 对待签名参数排序拼接并签名（排除 sign / sign_type 及空值）。
     */
    private String sign(Map<String, String> params, String privateKey) {
        String content = buildSignContent(params);
        return PaymentCryptoUtils.sign(PaymentCryptoUtils.parsePrivateKey(privateKey), content);
    }

    private String buildSignContent(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if ("sign".equals(key) || "sign_type".equals(key)) continue;
            if (value == null || value.isEmpty()) continue;
            sorted.put(key, value);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    private String postForm(String url, Map<String, String> params) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> response = restTemplate.postForEntity(
                url, new HttpEntity<>(form, headers), String.class);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readApiResponse(Map<String, Object> resp, String responseKey) {
        Object apiRespObj = resp.get(responseKey);
        if (!(apiRespObj instanceof Map<?, ?> apiResp)) {
            log.error("Alipay response missing key {}: {}", responseKey, resp);
            throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "支付宝响应格式异常");
        }
        return (Map<String, Object>) apiResp;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
