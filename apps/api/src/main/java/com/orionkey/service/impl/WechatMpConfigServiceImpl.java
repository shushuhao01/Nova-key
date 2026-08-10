package com.orionkey.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.entity.SiteConfig;
import com.orionkey.exception.BusinessException;
import com.orionkey.constant.ErrorCode;
import com.orionkey.repository.SiteConfigRepository;
import com.orionkey.service.WechatMpConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WechatMpConfigServiceImpl implements WechatMpConfigService {

    private final SiteConfigRepository siteConfigRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.base-url:https://noepay.cn}")
    private String baseUrl;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String RANDOM_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    public static final String KEY_APPID = "wx_mp_appid";
    public static final String KEY_APPSECRET = "wx_mp_appsecret";
    public static final String KEY_FOLLOW_QR = "wx_mp_follow_qr";
    public static final String KEY_TEMPLATE_ID = "wx_mp_template_id";
    /** 服务号消息模板列表（JSON 数组：code/name/description/variables/template_id/enabled） */
    public static final String KEY_MESSAGE_TEMPLATES = "wx_mp_message_templates";
    /** 服务器配置：Token（验证消息来源） */
    public static final String KEY_TOKEN = "wx_mp_token";
    /** 服务器配置：EncodingAESKey（安全/兼容模式消息解密，43 位） */
    public static final String KEY_AES_KEY = "wx_mp_aes_key";
    /** 服务器配置：消息加解密方式 plain / compatible / safe */
    public static final String KEY_ENCRYPT_MODE = "wx_mp_encrypt_mode";
    private static final String GROUP = "WECHAT_MP";

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appid", get(KEY_APPID));
        m.put("appsecret", get(KEY_APPSECRET));
        m.put("follow_qr", get(KEY_FOLLOW_QR));
        m.put("template_id", get(KEY_TEMPLATE_ID));
        m.put("message_templates", getMessageTemplates());
        // 服务器配置（推送接入）
        m.put("server_url", getServerUrl());
        m.put("token", get(KEY_TOKEN));
        m.put("aes_key", get(KEY_AES_KEY));
        m.put("encrypt_mode", getEncryptMode());
        m.put("data_format", "XML");
        // 公开登录绑定链接（放入服务号菜单/关注回复）
        m.put("bind_link", getBindLink());
        m.put("configured", isConfigured());
        return m;
    }

    @Override
    @Transactional
    public void updateConfig(Map<String, Object> body) {
        String appid = body.get("appid") != null ? body.get("appid").toString().trim() : "";
        String appsecret = body.get("appsecret") != null ? body.get("appsecret").toString().trim() : "";
        String followQr = body.get("follow_qr") != null ? body.get("follow_qr").toString().trim() : "";
        String templateId = body.get("template_id") != null ? body.get("template_id").toString().trim() : "";
        if (!appid.isBlank() && appsecret.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "填写了 AppID 则必须同时填写 AppSecret");
        }
        if (!appid.isBlank() && appid.length() < 10) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "AppID 格式不正确（应为 wx 开头的一串字符）");
        }
        // 服务器配置
        String token = body.get("token") != null ? body.get("token").toString().trim() : "";
        String aesKey = body.get("aes_key") != null ? body.get("aes_key").toString().trim() : "";
        String encryptMode = body.get("encrypt_mode") != null ? body.get("encrypt_mode").toString().trim() : "";
        if (token != null && token.length() > 64) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Token 过长（建议 32 位随机字符串）");
        }
        if (!aesKey.isBlank() && aesKey.length() != 43) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "EncodingAESKey 必须为 43 位字符");
        }
        if (!encryptMode.isBlank() && !List.of("plain", "compatible", "safe").contains(encryptMode)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "消息加解密方式无效");
        }
        set(KEY_APPID, appid);
        set(KEY_APPSECRET, appsecret);
        set(KEY_FOLLOW_QR, followQr);
        set(KEY_TEMPLATE_ID, templateId);
        set(KEY_TOKEN, token);
        set(KEY_AES_KEY, aesKey);
        set(KEY_ENCRYPT_MODE, encryptMode);
        // 消息模板列表（仅接受 List，防止非法 JSON 覆盖）
        if (body.get("message_templates") instanceof List<?> list) {
            set(KEY_MESSAGE_TEMPLATES, writeTemplates(list));
        }
        log.info("Wechat MP config updated, appid={}, configured={}, encryptMode={}", appid, !appid.isBlank(), encryptMode);
    }

    /** 读取消息模板列表；未配置时返回预置模板（幂等，不覆盖后台已填的模板 ID） */
    private List<Map<String, Object>> getMessageTemplates() {
        List<Map<String, Object>> preset = presetTemplates();
        String json = get(KEY_MESSAGE_TEMPLATES);
        if (json == null || json.isBlank()) {
            return preset;
        }
        try {
            List<Map<String, Object>> saved = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            Map<String, Map<String, Object>> savedByCode = new LinkedHashMap<>();
            for (Map<String, Object> t : saved) {
                if (t.get("code") != null) {
                    savedByCode.put(t.get("code").toString(), t);
                }
            }
            List<Map<String, Object>> merged = new ArrayList<>();
            for (Map<String, Object> p : preset) {
                Map<String, Object> s = savedByCode.get(p.get("code"));
                Map<String, Object> item = new LinkedHashMap<>(p);
                if (s != null) {
                    // 仅回填管理员填写的模板 ID 与启用状态
                    item.put("template_id", s.get("template_id") != null ? s.get("template_id") : "");
                    item.put("enabled", Boolean.TRUE.equals(s.get("enabled")));
                }
                merged.add(item);
                savedByCode.remove(p.get("code"));
            }
            // 保留后台新增的自定义模板（追加在末尾）
            merged.addAll(savedByCode.values());
            return merged;
        } catch (Exception e) {
            log.warn("Failed to parse wechat mp message templates, fallback to preset: {}", e.getMessage());
            return preset;
        }
    }

    private String writeTemplates(List<?> list) {
        // 仅保留已知字段，丢弃前端可能附带的多余字段
        List<Map<String, Object>> clean = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", m.get("code") != null ? m.get("code").toString() : "");
            item.put("name", m.get("name") != null ? m.get("name").toString() : "");
            item.put("description", m.get("description") != null ? m.get("description").toString() : "");
            Object vars = m.get("variables");
            item.put("variables", vars instanceof List<?> ? vars : List.of());
            item.put("template_id", m.get("template_id") != null ? m.get("template_id").toString() : "");
            item.put("enabled", Boolean.TRUE.equals(m.get("enabled")));
            clean.add(item);
        }
        try {
            return objectMapper.writeValueAsString(clean);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "消息模板数据格式错误");
        }
    }

    /** 预置服务号消息模板（管理员申请对应模板后填入模板 ID 即可） */
    private List<Map<String, Object>> presetTemplates() {
        return List.of(
                tmpl("ORDER_CARDKEY_DELIVERED", "订单卡密发货通知",
                        "客户购买卡密/虚拟商品自动发货后推送",
                        List.of("订单号", "商品名称", "卡密数量", "发货时间")),
                tmpl("ORDER_PAID_NOTIFY", "订单支付成功通知",
                        "客户支付成功后推送",
                        List.of("订单号", "支付金额", "支付时间")),
                tmpl("ORDER_DELIVERED_NOTIFY", "订单发货通知",
                        "订单发货后推送（含物流信息）",
                        List.of("订单号", "商品名称", "物流信息", "发货时间")),
                tmpl("DIST_CUSTOMER_PAID", "推广客户付款通知",
                        "客户通过您的推广链接下单付款后推送",
                        List.of("客户昵称", "商品名称", "付款金额", "我的佣金", "付款时间")),
                tmpl("DIST_COMMISSION_SETTLED", "佣金到账通知",
                        "佣金结算到账后推送",
                        List.of("佣金金额", "关联订单", "到账时间")),
                tmpl("SUB_ORDER_COMMISSION", "下级分销员成交订单通知",
                        "我的下级分销员成交订单并产生抽成时推送",
                        List.of("下级昵称", "商品名称", "订单金额", "我的抽成", "成交时间")),
                tmpl("WITHDRAWAL_REVIEW_RESULT", "佣金提现审核结果通知",
                        "提现申请审核通过/拒绝后推送",
                        List.of("提现金额", "审核状态", "审核原因", "处理时间")),
                tmpl("WITHDRAWAL_SUCCESS", "佣金提现成功通知",
                        "提现打款成功后推送",
                        List.of("提现金额", "到账账号", "到账时间")),
                tmpl("DIST_APPLY_RESULT", "分销员申请结果通知",
                        "分销员申请审核通过/拒绝后推送",
                        List.of("申请状态", "拒绝原因", "处理时间"))
        );
    }

    private Map<String, Object> tmpl(String code, String name, String description, List<String> variables) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("description", description);
        m.put("variables", variables);
        m.put("template_id", "");
        m.put("enabled", false);
        return m;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> testConfig() {
        String appid = get(KEY_APPID);
        String appsecret = get(KEY_APPSECRET);
        List<Map<String, Object>> items = new ArrayList<>();

        if (appid.isBlank() || appsecret.isBlank()) {
            items.add(item("AppID / AppSecret", "FAIL", "未配置：请先在下方填写公众号 AppID 与 AppSecret"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("passed", false);
            result.put("items", items);
            return result;
        }

        items.add(item("基础配置", "PASS", "AppID / AppSecret 已填写"));

        // 服务器配置检查（URL/Token/加解密）
        String token = get(KEY_TOKEN);
        String aesKey = get(KEY_AES_KEY);
        String mode = getEncryptMode();
        if (token.isBlank()) {
            items.add(item("服务器配置", "WARN", "回调 URL: " + getServerUrl() + "；尚未配置 Token，请点击「随机生成」并保存，再将 URL 与 Token 填入微信公众平台「设置与开发 → 基本配置 → 服务器配置」"));
        } else {
            items.add(item("服务器配置", "PASS", "URL: " + getServerUrl() + "；Token 已配置（" + maskSecret(token) + "）。请到微信公众平台「服务器配置」填写并保存，微信将立即向该 URL 发起验证"));
        }
        if (!"plain".equals(mode)) {
            if (aesKey.isBlank()) {
                items.add(item("消息加解密", "WARN", "当前为「" + modeName(mode) + "」，请生成并填写 EncodingAESKey（43 位）"));
            } else {
                items.add(item("消息加解密", "PASS", "EncodingAESKey 已配置（" + maskSecret(aesKey) + "），" + modeName(mode) + "可用"));
            }
        } else {
            items.add(item("消息加解密", "PASS", "明文模式：无需 EncodingAESKey（数据格式为 XML）"));
        }

        // 调微信 token 接口验证
        String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid="
                + appid + "&secret=" + appsecret;
        try {
            String body = restTemplate.getForObject(url, String.class);
            JsonNode json = objectMapper.readTree(body);
            if (json.has("access_token") && json.get("access_token").asText() != null
                    && !json.get("access_token").asText().isBlank()) {
                items.add(item("微信服务器连通", "PASS", "获取 access_token 成功，AppID/AppSecret 有效"));
                items.add(item("关注二维码", "PASS", isFollowQrSet() ? "已配置，分销中心将展示引导关注" : "未配置：可在下方上传公众号二维码后展示"));
                items.add(item("消息模板", "PASS", "模板消息需在微信公众平台申请后填写模板 ID（可选）"));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("passed", true);
                result.put("items", items);
                return result;
            }
            int errcode = json.has("errcode") ? json.get("errcode").asInt() : -1;
            String errmsg = json.has("errmsg") ? json.get("errmsg").asText() : "未知错误";
            items.add(item("微信服务器连通", "FAIL", "获取 token 失败：errcode=" + errcode + "，errmsg=" + errmsg + "。请检查 AppID/AppSecret 是否正确"));
            if (errcode == 40013) {
                items.add(item("排查建议", "INFO", "AppID 无效，请复制公众号后台「设置与开发 → 基本配置」中的 AppID"));
            } else if (errcode == 40125) {
                items.add(item("排查建议", "INFO", "AppSecret 无效，请核对公众号后台 AppSecret，注意不要带空格或换行"));
            } else if (errcode == -1) {
                items.add(item("排查建议", "INFO", "微信服务器繁忙，请稍后重试"));
            }
        } catch (Exception e) {
            log.warn("Wechat MP test failed: {}", e.getMessage());
            items.add(item("微信服务器连通", "FAIL", "无法访问微信服务器：" + e.getMessage() + "（请检查服务器网络与 DNS）"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", false);
        result.put("items", items);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getFollowInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        String qr = get(KEY_FOLLOW_QR);
        m.put("configured", qr != null && !qr.isBlank());
        m.put("follow_qr", qr);
        m.put("mp_appid", get(KEY_APPID));
        return m;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isConfigured() {
        return !get(KEY_APPID).isBlank() && !get(KEY_APPSECRET).isBlank();
    }

    @Override
    @Transactional(readOnly = true)
    public String getAppid() {
        return get(KEY_APPID);
    }

    @Override
    @Transactional(readOnly = true)
    public String getAppsecret() {
        return get(KEY_APPSECRET);
    }

    @Override
    @Transactional(readOnly = true)
    public String getBindLink() {
        return baseUrl + "/wechat/mp-bind";
    }

    @Override
    @Transactional(readOnly = true)
    public String getServerUrl() {
        return baseUrl + "/api/wechat-mp/callback";
    }

    @Override
    @Transactional(readOnly = true)
    public String getToken() {
        return get(KEY_TOKEN);
    }

    @Override
    @Transactional(readOnly = true)
    public String getAesKey() {
        return get(KEY_AES_KEY);
    }

    @Override
    @Transactional(readOnly = true)
    public String getEncryptMode() {
        String mode = get(KEY_ENCRYPT_MODE);
        return mode.isBlank() ? "plain" : mode;
    }

    @Override
    @Transactional(readOnly = true)
    public String generateToken() {
        return randomString(32);
    }

    @Override
    @Transactional(readOnly = true)
    public String generateAesKey() {
        return randomString(43);
    }

    // ── 微信用户资料（头像/昵称） ──

    private volatile String cachedAccessToken = "";
    private volatile long cachedAccessTokenAt = 0L;
    /** access_token 缓存时长：100 分钟（微信有效期为 7200 秒，留有余量） */
    private static final long ACCESS_TOKEN_TTL_MS = 6_000_000L;

    @Override
    @Transactional(readOnly = true)
    public String getAccessToken() {
        long now = System.currentTimeMillis();
        if (!cachedAccessToken.isBlank() && now - cachedAccessTokenAt < ACCESS_TOKEN_TTL_MS) {
            return cachedAccessToken;
        }
        String appid = get(KEY_APPID);
        String appsecret = get(KEY_APPSECRET);
        if (appid.isBlank() || appsecret.isBlank()) {
            return "";
        }
        String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid="
                + appid + "&secret=" + appsecret;
        try {
            String body = restTemplate.getForObject(url, String.class);
            JsonNode json = objectMapper.readTree(body);
            if (json.has("access_token") && !json.get("access_token").asText().isBlank()) {
                cachedAccessToken = json.get("access_token").asText();
                cachedAccessTokenAt = now;
                return cachedAccessToken;
            }
            log.warn("Wechat MP getAccessToken failed: {}", body);
        } catch (Exception e) {
            log.warn("Wechat MP getAccessToken error: {}", e.getMessage());
        }
        return "";
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> fetchUserProfile(String openid) {
        if (openid == null || openid.isBlank()) {
            return null;
        }
        String token = getAccessToken();
        if (token.isBlank()) {
            return null;
        }
        String url = "https://api.weixin.qq.com/cgi-bin/user/info?access_token=" + token
                + "&openid=" + openid + "&lang=zh_CN";
        try {
            String body = restTemplate.getForObject(url, String.class);
            JsonNode json = objectMapper.readTree(body);
            if (json.has("subscribe") && json.get("subscribe").asInt() == 1) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nickname", json.has("nickname") ? json.get("nickname").asText() : "");
                m.put("headimgurl", json.has("headimgurl") ? json.get("headimgurl").asText() : "");
                m.put("subscribe", 1);
                return m;
            }
            log.info("Wechat MP user not subscribed: {}", openid);
        } catch (Exception e) {
            log.warn("Wechat MP fetchUserProfile error for {}: {}", openid, e.getMessage());
        }
        return null;
    }

    // ── 工具 ──

    private String randomString(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(RANDOM_CHARS.charAt(RANDOM.nextInt(RANDOM_CHARS.length())));
        }
        return sb.toString();
    }

    private String modeName(String mode) {
        return switch (mode) {
            case "compatible" -> "兼容模式";
            case "safe" -> "安全模式";
            default -> "明文模式";
        };
    }

    /** 敏感信息打码展示（保留前 4 后 4） */
    private String maskSecret(String s) {
        if (s == null || s.length() <= 8) return "****";
        return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }

    private boolean isFollowQrSet() {
        return !get(KEY_FOLLOW_QR).isBlank();
    }

    private Map<String, Object> item(String name, String status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("status", status);
        m.put("message", message);
        return m;
    }

    private String get(String key) {
        return siteConfigRepository.findByConfigKey(key).map(SiteConfig::getConfigValue).orElse("");
    }

    private void set(String key, String value) {
        SiteConfig sc = siteConfigRepository.findByConfigKey(key).orElseGet(() -> {
            SiteConfig n = new SiteConfig();
            n.setConfigKey(key);
            n.setConfigGroup(GROUP);
            return n;
        });
        sc.setConfigValue(value == null ? "" : value);
        siteConfigRepository.save(sc);
    }
}
