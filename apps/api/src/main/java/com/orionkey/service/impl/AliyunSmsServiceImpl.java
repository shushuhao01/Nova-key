package com.orionkey.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.constant.ErrorCode;
import com.orionkey.exception.BusinessException;
import com.orionkey.service.SiteConfigService;
import com.orionkey.service.SmsService;
import com.orionkey.util.PhoneUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 阿里云短信服务实现（RPC 风格 GET 请求 + HMAC-SHA1 签名）。
 * <p>
 * 未启用 / 未配置时直接失败并给出明确提示，<b>不做任何模拟成功</b>，
 * 避免前台出现「提示已发送但实际收不到」的静默故障。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AliyunSmsServiceImpl implements SmsService {

    private static final String ENDPOINT = "https://dysmsapi.aliyuncs.com/";
    private static final String API_VERSION = "2017-05-25";
    private static final DateTimeFormatter UTC_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private final SiteConfigService siteConfigService;
    private final ObjectMapper objectMapper;

    private final RestClient restClient = RestClient.builder().build();

    // ═══════════ 配置读取 ═══════════

    @Override
    public boolean isEnabled() {
        return "true".equalsIgnoreCase(cfg("sms_enabled"));
    }

    @Override
    public boolean isConfigured() {
        return StringUtils.hasText(cfg("sms_access_key_id"))
                && StringUtils.hasText(cfg("sms_access_key_secret"))
                && StringUtils.hasText(cfg("sms_sign_name"));
    }

    @Override
    public String templateCodeForScene(String scene) {
        String key = switch (scene == null ? "" : scene.trim().toUpperCase(Locale.ROOT)) {
            case "LOGIN" -> "sms_tpl_login_code";
            case "PASSWORD_RESET" -> "sms_tpl_password_reset";
            case "BIND_PHONE" -> "sms_tpl_bind_phone";
            default -> "sms_tpl_verify_code";
        };
        String value = cfg(key);
        return StringUtils.hasText(value) ? value : null;
    }

    private String cfg(String key) {
        return siteConfigService.getValue(key, "").trim();
    }

    private String siteName() {
        String name = siteConfigService.getValue("site_name", "").trim();
        return StringUtils.hasText(name) ? name : "Nova key";
    }

    // ═══════════ 发送 ═══════════

    @Override
    public void sendVerifyCode(String phone, String scene, String code, int expireMinutes) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("code", code);
        String upper = scene == null ? "" : scene.trim().toUpperCase(Locale.ROOT);
        if ("LOGIN".equals(upper) || "PASSWORD_RESET".equals(upper)) {
            params.put("site_name", siteName());
        }
        // 模板 CODE 为空时由 sendTemplate 抛出「短信模板未配置」
        sendTemplate(phone, templateCodeForScene(upper), params);
    }

    @Override
    public void sendTemplate(String phone, String templateCode, Map<String, Object> params) {
        if (!isEnabled()) {
            throw new BusinessException(ErrorCode.SMS_NOT_ENABLED, "短信服务未启用");
        }
        if (!isConfigured()) {
            throw new BusinessException(ErrorCode.SMS_NOT_CONFIGURED,
                    "短信服务未配置，请先在后台填写 AccessKeyId / AccessKeySecret / 短信签名");
        }
        String normalized = PhoneUtils.normalize(phone);
        if (!PhoneUtils.isValidCnMobile(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号格式不正确");
        }
        if (!StringUtils.hasText(templateCode)) {
            throw new BusinessException(ErrorCode.SMS_NOT_CONFIGURED, "短信模板未配置，请先在后台填写模板 CODE");
        }

        Map<String, String> templateParam = new LinkedHashMap<>();
        if (params != null) {
            params.forEach((k, v) -> {
                if (k != null && v != null) {
                    templateParam.put(k, String.valueOf(v));
                }
            });
        }

        Map<String, Object> result;
        try {
            Map<String, Object> biz = new LinkedHashMap<>();
            biz.put("PhoneNumbers", normalized);
            biz.put("SignName", cfg("sms_sign_name"));
            biz.put("TemplateCode", templateCode.trim());
            biz.put("TemplateParam", objectMapper.writeValueAsString(templateParam));
            result = invoke("SendSms", biz);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("SMS send failed: phone={}, template={}, err={}", mask(normalized), templateCode, e.getMessage());
            throw new BusinessException(ErrorCode.SMS_SEND_FAILED, "短信发送失败：网络异常，请稍后重试");
        }

        String code = str(result.get("Code"));
        if (!"OK".equalsIgnoreCase(code)) {
            String message = friendly(code, str(result.get("Message")));
            log.warn("SMS send rejected: phone={}, template={}, code={}, message={}",
                    mask(normalized), templateCode, code, result.get("Message"));
            throw new BusinessException(ErrorCode.SMS_SEND_FAILED, message);
        }
        log.info("SMS sent: phone={}, template={}, bizId={}", mask(normalized), templateCode, result.get("BizId"));
    }

    // ═══════════ 测试 ═══════════

    @Override
    public Map<String, Object> testConnection() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!isConfigured()) {
            out.put("passed", false);
            out.put("message", "短信服务未配置：请先填写 AccessKeyId / AccessKeySecret / 短信签名");
            return out;
        }
        // 1. 只读接口（模板列表）校验 AK/SK 与签名，不产生费用
        Map<String, Object> result;
        try {
            result = invoke("QuerySmsTemplateList", Map.<String, Object>of("PageIndex", 1, "PageSize", 1));
        } catch (Exception e) {
            out.put("passed", false);
            out.put("message", "无法连接阿里云短信服务：" + e.getMessage());
            return out;
        }
        String code = str(result.get("Code"));
        if ("OK".equalsIgnoreCase(code)) {
            out.put("passed", true);
            out.put("message", "AK/SK 校验通过（阿里云只读接口可用）");
            return out;
        }
        // 2. RAM 未授权只读接口时退化为「发送一条测试短信」，同样能验证签名与签名名称
        if (code.startsWith("Forbidden")) {
            String adminPhone = cfg("sms_admin_phone");
            String tpl = templateCodeForScene("REGISTER");
            if (!StringUtils.hasText(adminPhone) || !StringUtils.hasText(tpl)) {
                out.put("passed", false);
                out.put("message", "只读接口权限不足（" + code + "），且未配置管理员手机号 / 通用验证码模板，无法退化验证");
                return out;
            }
            try {
                sendTemplate(adminPhone, tpl, Map.<String, Object>of("code", "123456"));
                out.put("passed", true);
                out.put("message", "只读接口权限不足，已退化为发送测试短信至 " + mask(adminPhone) + "，发送成功");
            } catch (BusinessException e) {
                out.put("passed", false);
                out.put("message", e.getMessage());
            }
            return out;
        }
        out.put("passed", false);
        out.put("message", friendly(code, str(result.get("Message"))));
        return out;
    }

    @Override
    public Map<String, Object> testSend(String phone, String templateCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!StringUtils.hasText(phone)) {
            out.put("passed", false);
            out.put("message", "请输入测试接收手机号");
            return out;
        }
        String tpl = StringUtils.hasText(templateCode) ? templateCode.trim() : templateCodeForScene("REGISTER");
        if (!StringUtils.hasText(tpl)) {
            out.put("passed", false);
            out.put("message", "请先填写通用验证码模板 CODE");
            return out;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("code", "123456");
        params.put("site_name", siteName());
        try {
            sendTemplate(phone, tpl, params);
            out.put("passed", true);
            out.put("message", "测试短信已发送，请查收（模板：" + tpl + "）");
        } catch (BusinessException e) {
            out.put("passed", false);
            out.put("message", e.getMessage());
        }
        return out;
    }

    // ═══════════ 阿里云调用与签名 ═══════════

    /**
     * 调用阿里云 RPC 接口：组装公共参数 + 业务参数 → HMAC-SHA1 签名 → GET 请求 → 解析 JSON。
     * 阿里云业务错误同样以 HTTP 400 + JSON 返回，这里统一解析为 Map 交由调用方判断 Code。
     */
    private Map<String, Object> invoke(String action, Map<String, Object> bizParams) {
        String accessKeyId = cfg("sms_access_key_id");
        String accessKeySecret = cfg("sms_access_key_secret");
        String region = cfg("sms_region");
        if (!StringUtils.hasText(region)) {
            region = "cn-hangzhou";
        }

        Map<String, String> params = new TreeMap<>();
        params.put("AccessKeyId", accessKeyId);
        params.put("Action", action);
        params.put("Format", "JSON");
        params.put("RegionId", region);
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("SignatureVersion", "1.0");
        params.put("Timestamp", ZonedDateTime.now(ZoneOffset.UTC).format(UTC_TS));
        params.put("Version", API_VERSION);
        if (bizParams != null) {
            bizParams.forEach((k, v) -> {
                if (k != null && v != null) {
                    params.put(k, String.valueOf(v));
                }
            });
        }
        params.put("Signature", sign(params, accessKeySecret));

        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(percentEncode(e.getKey())).append('=').append(percentEncode(e.getValue()));
        }

        String body;
        try {
            body = restClient.get().uri(URI.create(ENDPOINT + "?" + query)).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            body = e.getResponseBodyAsString();
            if (!StringUtils.hasText(body)) {
                throw new IllegalStateException("HTTP " + e.getStatusCode().value() + " " + e.getStatusText());
            }
        }
        if (!StringUtils.hasText(body)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("Invalid SMS response body: {}", body);
            return Map.of();
        }
    }

    /** 阿里云签名：GET&amp;%2F&amp;percentEncode(按 key 升序的 k=v&amp;...)，key = secret + "&amp;" */
    private String sign(Map<String, String> params, String accessKeySecret) {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
            if ("Signature".equals(e.getKey())) {
                continue;
            }
            if (!canonical.isEmpty()) {
                canonical.append('&');
            }
            canonical.append(percentEncode(e.getKey())).append('=').append(percentEncode(e.getValue()));
        }
        String stringToSign = "GET&" + percentEncode("/") + "&" + percentEncode(canonical.toString());
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((accessKeySecret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("短信签名计算失败", e);
        }
    }

    /** RFC3986 编码：空格 → %20、* → %2A、~ 不编码 */
    private String percentEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    // ═══════════ 错误码映射 ═══════════

    private String friendly(String code, String message) {
        String mapped = switch (code == null ? "" : code) {
            case "isv.BUSINESS_LIMIT_CONTROL" -> "短信发送频率超限（阿里云流控），请稍后再试";
            case "isv.DAY_LIMIT_CONTROL" -> "该手机号今日接收短信已达上限，请明天再试";
            case "isv.MOBILE_NUMBER_ILLEGAL" -> "手机号格式不正确";
            case "isv.AMOUNT_NOT_ENOUGH" -> "短信账户余额不足，请在阿里云充值";
            case "isv.OUT_OF_SERVICE" -> "短信业务已停机，请检查阿里云账户状态";
            case "SignatureDoesNotMatch" -> "AccessKeySecret 错误（签名不匹配），请检查短信配置";
            case "InvalidAccessKeyId.NotFound" -> "AccessKeyId 不存在，请检查短信配置";
            case "isv.SMS_SIGNATURE_ILLEGAL" -> "短信签名不合法，请与已审核通过的签名保持一致";
            case "isv.SMS_TEMPLATE_ILLEGAL" -> "短信模板不合法或未审核通过";
            case "isv.TEMPLATE_MISSING_PARAMETERS" -> "短信模板参数缺失，请检查模板变量";
            case "isv.TEMPLATE_PARAMS_ILLEGAL" -> "短信模板参数不合法（变量名或长度不符合要求）";
            case "Forbidden.RAM" -> "当前 AccessKey 无短信接口权限，请在阿里云 RAM 中授权";
            default -> StringUtils.hasText(message)
                    ? "短信发送失败：" + message + "（" + code + "）"
                    : "短信发送失败（" + code + "）";
        };
        return mapped;
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
