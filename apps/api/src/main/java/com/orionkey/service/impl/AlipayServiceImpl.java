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
import org.springframework.web.util.UriComponentsBuilder;

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

        // 5. 连接测试：AppID + 商家私钥齐全时，用一笔不存在的订单号调用查询接口。
        //    判定标准与 CRM 一致：支付宝网关是否接受商家私钥签名（返回业务响应即签名通过），
        //    不依赖支付宝公钥；公钥正确性由第 6 项「响应验签」单独校验。
        boolean connOk = false;
        String connMsg;
        // 供第 6 项「响应验签」复用：网关返回的业务响应 + 响应签名
        Map<String, Object> apiRespForVerify = null;
        String respSign = null;
        if (!hasAppId || !hasPrivateKey) {
            connMsg = "配置不完整（缺少AppID或商家私钥），无法进行真实连接测试";
        } else {
            String outTradeNo = "NOVA_TEST_" + System.currentTimeMillis();
            String bizContent = "{\"out_trade_no\":\"" + outTradeNo + "\"}";
            Map<String, String> params = baseParams(config, "alipay.trade.query", bizContent);
            // 诊断用：记录系统实际签名内容与签名值，isv.invalid-signature 时随错误信息返回，
            // 便于与手工脚本(test-alipay-sign.sh)逐字节对比定位差异
            String debugContent = null;
            String debugSign = null;
            String debugUrl = null;
            try {
                debugContent = buildSignContent(params, true);
                String sign = sign(params, config.privateKey());
                debugSign = sign;
                params.put("sign", sign);
                // 复刻 postForm 的 URL 构建逻辑，诊断编码后的最终请求（含 body）
                Map<String, String> q = new LinkedHashMap<>(params);
                String biz = q.remove("biz_content");
                UriComponentsBuilder dbgBuilder = UriComponentsBuilder.fromHttpUrl(config.gatewayUrl());
                q.forEach(dbgBuilder::queryParam);
                debugUrl = dbgBuilder.build().encode().toUri().toString()
                        + (biz != null ? " | body=" + biz : "");
                log.info("ALIPAY_TEST content={} sign={} url={}", debugContent, debugSign, debugUrl);
                String respBody = postForm(config.gatewayUrl(), params);
                Map<String, Object> resp = objectMapper.readValue(respBody, new TypeReference<>() {
                });

                // 网关返回 error_response：请求未被接受（通常是签名错误）
                Object errObj = resp.get("error_response");
                if (errObj instanceof Map<?, ?> em) {
                    Map<String, Object> errorResp = (Map<String, Object>) em;
                    String subCode = errorResp.get("sub_code") != null
                            ? errorResp.get("sub_code").toString() : null;
                    String errMsg = errorResp.get("sub_msg") != null
                            ? errorResp.get("sub_msg").toString()
                            : (errorResp.get("msg") != null ? errorResp.get("msg").toString() : "");
                    if ("isv.invalid-signature".equals(subCode)) {
                        connMsg = "支付宝网关返回签名错误（isv.invalid-signature）：请核对 支付应用Appid 与 支付宝商家私钥 是否匹配、私钥是否完整（含 -----BEGIN PRIVATE KEY----- 头）";
                    } else {
                        connMsg = "支付宝网关返回错误：" + (subCode != null ? subCode + " " : "") + errMsg;
                    }
                } else {
                    // 网关接受了签名并返回业务响应（订单不存在也返回正常响应）
                    Map<String, Object> apiResp = readApiResponse(resp, "alipay_trade_query_response");
                    String code = apiResp.get("code") != null ? apiResp.get("code").toString() : null;
                    // code=10000 成功；code=40004(ACQ.TRADE_NOT_EXIST) 表示签名验证通过、仅订单不存在（测试单号必然不存在）
                    if ("10000".equals(code) || "40004".equals(code)) {
                        connOk = true;
                        connMsg = "支付宝网关连接成功，AppID 与商家私钥签名验证通过";
                        apiRespForVerify = apiResp;
                        if (resp.get("sign") instanceof String s) {
                            respSign = s;
                        }
                    } else {
                        // 业务响应携带 code=40002（Invalid Arguments）时，真正原因在 sub_code：
                        // isv.invalid-app-id（AppID 无效）、isv.invalid-signature（私钥/签名不匹配）、
                        // isv.invalid-parameter（参数非法）等。完整展示并给出针对性指引。
                        String subCode = apiResp.get("sub_code") != null ? apiResp.get("sub_code").toString() : null;
                        String subMsg = apiResp.get("sub_msg") != null ? apiResp.get("sub_msg").toString() : null;
                        StringBuilder sb = new StringBuilder("支付宝接口返回错误：code=")
                                .append(code).append("（msg=").append(apiResp.get("msg")).append("）");
                        if (subCode != null || subMsg != null) {
                            sb.append("；详情：")
                                    .append(subCode != null ? subCode : "")
                                    .append(" ").append(subMsg != null ? subMsg : "");
                        }
                        if ("isv.invalid-app-id".equals(subCode)) {
                            sb.append("。AppID 无效：请确认填入的是支付宝开放平台「正式环境」支付应用的 AppID（open.alipay.com 应用详情页），且应用已上线；勿使用沙箱环境的 AppID（网关固定为正式网关 openapi.alipay.com）");
                        } else if ("isv.invalid-signature".equals(subCode)) {
                            sb.append("。签名校验失败：")
                                    .append(checkPrivateKeyBits(config.privateKey()))
                                    .append(privateKeyFingerprintHint(config.privateKey()))
                                    .append("请核对 AppID 对应的「应用私钥」是否匹配（在开放平台重新生成并上传应用公钥后，使用其配套的私钥），并确认应用「加签方式」为「公钥」（若为「公钥证书」则需使用证书模式，当前系统不支持）")
                                    .append("。系统实际验签串=").append(debugContent)
                                    .append(" 系统sign=").append(debugSign)
                                    .append(" 系统请求URL=").append(debugUrl);
                        } else if ("isv.invalid-parameter".equals(subCode)) {
                            sb.append("。请求参数无效：请检查 AppID/私钥/公钥粘贴时是否带有多余空格、换行等非法字符");
                        }
                        connMsg = sb.toString();
                    }
                }
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

        // 6. 响应验签（支付宝公钥）：用填写的公钥验证支付宝响应的 sign，
        //    判断填入的是否真的是「支付宝公钥」（而非「应用公钥」）。
        //    该项不影响连接测试结果，但影响支付回调验签是否成功（回调验签失败将无法发货）。
        boolean respVerifyOk = false;
        String respVerifyMsg = "";
        if (!connOk) {
            respVerifyMsg = "未执行（连接测试未通过）";
        } else if (respSign == null || respSign.isBlank()) {
            respVerifyMsg = "响应缺少 sign 字段，跳过响应验签（下单/回调时仍会验签）";
        } else {
            try {
                Map<String, String> verifyParams = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : apiRespForVerify.entrySet()) {
                    verifyParams.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
                String content = buildSignContent(verifyParams, false);
                java.security.PublicKey publicKey;
                try {
                    publicKey = PaymentCryptoUtils.parsePublicKey(config.alipayPublicKey());
                } catch (Exception e) {
                    publicKey = null;
                    respVerifyMsg = "支付宝公钥格式无法解析：" + e.getMessage()
                            + "。请重新从支付宝开放平台「密钥管理」复制完整的『支付宝公钥』（含 -----BEGIN PUBLIC KEY----- 头）并保存后重试";
                }
                if (publicKey != null) {
                    if (PaymentCryptoUtils.verify(publicKey, content, respSign)) {
                        respVerifyOk = true;
                        respVerifyMsg = "响应验签通过，支付宝公钥正确，支付回调可正常验签发货";
                    } else {
                        respVerifyMsg = "支付宝响应验签失败：当前填写的公钥无法验证支付宝的签名。请在支付宝开放平台「密钥管理」中复制『支付宝公钥』（注意不是『应用公钥』——应用公钥是您私钥配套的公钥，无法验证支付宝的签名）填入本字段";
                    }
                }
            } catch (Exception e) {
                respVerifyMsg = "响应验签异常：" + e.getMessage();
            }
        }
        items.add(new PaymentTestResult.TestItem("响应验签", respVerifyOk, respVerifyMsg));

        boolean passed = items.stream().allMatch(i -> i.status());
        String summary = passed ? "支付宝配置验证通过" : "部分配置项未通过验证，请根据上方 ❌ 项修正";
        return new PaymentTestResult(passed, items, summary);
    }

    /**
     * 私钥自检：用私钥推导出对应的「应用公钥」，计算其 X.509 DER 的 SHA-1 指纹。
     * 用户在支付宝开放平台「接口加签方式」页面复制『应用公钥』后，可在本地用
     * openssl 计算指纹与本指纹比对：
     *   openssl pkey -pubin -in app_public_key.pem -outform der | openssl dgst -sha1
     * 指纹一致说明私钥与当前 AppID 配套（问题在 AppID 或加签模式）；
     * 指纹不一致说明私钥是从其他应用/项目复制来的，与当前 AppID 不配套。
     */
    private static String privateKeyFingerprintHint(String privateKey) {
        try {
            java.security.PrivateKey key = PaymentCryptoUtils.parsePrivateKey(privateKey);
            if (key instanceof java.security.interfaces.RSAPrivateCrtKey crtKey) {
                java.security.PublicKey pub = java.security.KeyFactory.getInstance("RSA").generatePublic(
                        new java.security.spec.RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
                byte[] digest = java.security.MessageDigest.getInstance("SHA-1").digest(pub.getEncoded());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < digest.length; i++) {
                    if (i > 0) sb.append(':');
                    sb.append(String.format("%02X", digest[i]));
                }
                return "本配置私钥对应的『应用公钥』指纹：SHA1: " + sb
                        + "。请到开放平台复制『应用公钥』后本地比对（openssl pkey -pubin -in 公钥.pem -outform der | openssl dgst -sha1），指纹一致则私钥正确（问题在 AppID 或加签方式），不一致则私钥与当前 AppID 不配套；";
            }
        } catch (Exception ignored) {
            // 私钥无法解析时走「商家私钥解析失败」提示，这里忽略
        }
        return "";
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * RSA2（SHA256withRSA）要求密钥至少 2048 位。
     * 签名校验失败时本地检测私钥位数，若不足则给出明确提示。
     */
    private static String checkPrivateKeyBits(String privateKey) {
        try {
            java.security.PrivateKey key = PaymentCryptoUtils.parsePrivateKey(privateKey);
            if (key instanceof java.security.interfaces.RSAPrivateKey rsaKey) {
                int bits = rsaKey.getModulus().bitLength();
                if (bits < 2048) {
                    return "当前私钥仅 " + bits + " 位，RSA2 要求至少 2048 位，请用支付宝官方密钥工具重新生成 2048 位密钥对；";
                }
            }
        } catch (Exception ignored) {
            // 私钥无法解析时走「商家私钥解析失败」提示，这里忽略
        }
        return "";
    }

    @Override
    public boolean verifySign(String alipayPublicKey, Map<String, String> params, String sign) {
        if (sign == null || sign.isBlank()) return false;
        try {
            String content = buildSignContent(params, false);
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
        // notify_url 仅当面付/手机网站支付等需要异步通知的接口才传；
        // alipay.trade.query（含连接测试）接口未定义 notify_url 参数，
        // 携带该参数可能被支付宝判定参数无效（40002 Invalid Arguments）
        if (!"alipay.trade.query".equals(method)
                && config.notifyUrl() != null && !config.notifyUrl().isBlank()) {
            params.put("notify_url", config.notifyUrl());
        }
        params.put("biz_content", bizContent);
        return params;
    }

    /**
     * 请求签名：sign_type 必须参与签名！支付宝网关验签时生成的验签字符串
     * 包含 sign_type=RSA2（见网关错误信息中给出的"即将生成的验签字符串"），
     * 若将其排除会导致 isv.invalid-signature。
     */
    private String sign(Map<String, String> params, String privateKey) {
        String content = buildSignContent(params, true);
        return PaymentCryptoUtils.sign(PaymentCryptoUtils.parsePrivateKey(privateKey), content);
    }

    /**
     * 对待签名参数排序拼接。includeSignType 用于区分两种验签规则：
     * - 请求签名（includeSignType=true）：网关验签字符串含 sign_type，必须参与；
     * - 响应/异步通知验签（includeSignType=false）：支付宝通知验签规则剔除 sign 和 sign_type。
     * 仅排除 sign 及空值。
     */
    private String buildSignContent(Map<String, String> params, boolean includeSignType) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if ("sign".equals(key)) continue;
            if (!includeSignType && "sign_type".equals(key)) continue;
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
        // 支付宝网关请求规范：平台公共参数（app_id/method/charset/sign_type/timestamp/version/sign 等）
        // 必须放在 URL query 中（特别是 charset），业务参数 biz_content 放在 HTTP body 中。
        // 若把所有参数都放在 form body，网关验签会失败（isv.invalid-signature，
        // 提示"请确认charset参数放在了URL查询字符串中"）。
        String bizContent = params.remove("biz_content");
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url);
        params.forEach(builder::queryParam);
        java.net.URI uri = builder.build().encode().toUri();
        log.info("ALIPAY_REQ url={} body={}", uri, bizContent);
        if (bizContent != null) {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("biz_content", bizContent);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    uri, new HttpEntity<>(form, headers), String.class);
            return response.getBody();
        }
        ResponseEntity<String> response = restTemplate.postForEntity(uri, null, String.class);
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
