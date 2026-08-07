package com.orionkey.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.constant.ErrorCode;
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
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
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
        params.put("sign_type", "RSA2");
        params.put("timestamp", LocalDateTime.now().format(TIMESTAMP_FORMAT));
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
