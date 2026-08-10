package com.orionkey.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.entity.SiteConfig;
import com.orionkey.exception.BusinessException;
import com.orionkey.constant.ErrorCode;
import com.orionkey.repository.SiteConfigRepository;
import com.orionkey.service.WechatMpConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

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

    public static final String KEY_APPID = "wx_mp_appid";
    public static final String KEY_APPSECRET = "wx_mp_appsecret";
    public static final String KEY_FOLLOW_QR = "wx_mp_follow_qr";
    public static final String KEY_TEMPLATE_ID = "wx_mp_template_id";
    private static final String GROUP = "WECHAT_MP";

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appid", get(KEY_APPID));
        m.put("appsecret", get(KEY_APPSECRET));
        m.put("follow_qr", get(KEY_FOLLOW_QR));
        m.put("template_id", get(KEY_TEMPLATE_ID));
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
        set(KEY_APPID, appid);
        set(KEY_APPSECRET, appsecret);
        set(KEY_FOLLOW_QR, followQr);
        set(KEY_TEMPLATE_ID, templateId);
        log.info("Wechat MP config updated, appid={}, configured={}", appid, !appid.isBlank());
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

    // ── 工具 ──

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
