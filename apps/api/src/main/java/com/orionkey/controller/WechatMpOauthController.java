package com.orionkey.controller;

import com.orionkey.common.ApiResponse;
import com.orionkey.constant.ErrorCode;
import com.orionkey.exception.BusinessException;
import com.orionkey.service.WechatMpConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公众号微信内静默网页授权（snsapi_base，游客无需登录）换取 openid。
 * <p>
 * 用于微信浏览器内下单购买时走 JSAPI 支付（直接拉起微信支付）：
 * 1. 前端调用 {@code GET /wechat-mp/oauth2/url?redirect=...} 拿到微信授权链接；
 * 2. 微信在公众号内跳转授权，重定向回 {@code /wechat-mp/oauth2/callback}；
 * 3. 服务端用 code 换 openid 后，302 重定向回前端原始页面，并把 openid 以 {@code mp_openid} 参数带回。
 * <p>
 * 与 {@link WechatMpBindController}（需登录账号绑定）不同：本控制器面向游客，不写库、不要求登录。
 * 前提：公众号后台已配置「网页授权域名」为站点域名。
 */
@Slf4j
@RestController
@RequestMapping("/wechat-mp/oauth2")
@RequiredArgsConstructor
public class WechatMpOauthController {

    private final WechatMpConfigService mpConfigService;
    private final RestTemplate restTemplate;

    @Value("${app.base-url:https://noepay.cn}")
    private String baseUrl;

    /** state -> 回调跳转地址（防 CSRF / open redirect）；5 分钟内有效，超时自动清理 */
    private static final Map<String, OauthState> STATES = new ConcurrentHashMap<>();

    private record OauthState(String redirect, long expireAt) {
    }

    /**
     * 生成微信内静默授权链接（snsapi_base，游客无感知拿 openid）。
     *
     * @param redirect 授权成功后回跳的前端页面（须为站内路径，以 "/" 开头且不以 "//" 开头），
     *                 例如 /pay/xxx?method=wechat
     */
    @GetMapping("/url")
    public ApiResponse<Map<String, Object>> oauthUrl(@RequestParam String redirect) {
        if (!mpConfigService.isConfigured()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "公众号尚未配置 AppID/AppSecret，无法微信内支付");
        }
        // 仅允许站内路径，防止 open redirect
        String safeRedirect = sanitizeRedirect(redirect);
        String state = java.util.UUID.randomUUID().toString().replace("-", "");
        STATES.put(state, new OauthState(safeRedirect, System.currentTimeMillis() + 5 * 60_000L));

        String callbackUri = baseUrl + "/wechat-mp/oauth2/callback";
        String oauthUrl = "https://open.weixin.qq.com/connect/oauth2/authorize?appid="
                + mpConfigService.getAppid()
                + "&redirect_uri=" + URLEncoder.encode(callbackUri, StandardCharsets.UTF_8)
                + "&response_type=code&scope=snsapi_base&state=" + state + "#wechat_redirect";

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("oauth_url", oauthUrl);
        m.put("state", state);
        return ApiResponse.success(m);
    }

    /**
     * 微信网页授权回调：code 换 openid 后 302 重定向回前端页面（附带 mp_openid 参数）。
     * 该端点由微信服务器重定向访问，无 JWT，需在 SecurityConfig 放行。
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam String code,
                                         @RequestParam String state) {
        OauthState st = STATES.get(state);
        if (st == null || System.currentTimeMillis() > st.expireAt()) {
            STATES.remove(state);
            log.warn("Wechat OAuth callback: invalid or expired state={}", state);
            return ResponseEntity.status(302)
                    .header("Location", baseUrl + "/")
                    .build();
        }
        STATES.remove(state);

        String openid;
        try {
            String url = "https://api.weixin.qq.com/sns/oauth2/access_token?appid="
                    + mpConfigService.getAppid()
                    + "&secret=" + mpConfigService.getAppsecret()
                    + "&code=" + code + "&grant_type=authorization_code";
            String resp = restTemplate.getForObject(url, String.class);
            openid = extractJsonField(resp, "openid");
            if (openid == null || openid.isBlank()) {
                log.warn("Wechat OAuth code exchange failed, state={}, resp={}", state, resp);
                return ResponseEntity.status(302)
                        .header("Location", st.redirect())
                        .build();
            }
        } catch (Exception e) {
            log.error("Wechat OAuth code exchange error, state={}", state, e);
            return ResponseEntity.status(302)
                    .header("Location", st.redirect())
                    .build();
        }

        // 回跳前端页面并把 openid 以 mp_openid 参数带回（前端读取后存 sessionStorage）
        String location = appendQueryParam(st.redirect(), "mp_openid", openid);
        return ResponseEntity.status(302).header("Location", location).build();
    }

    /** 仅允许站内相对路径（以 "/" 开头且不以 "//" 开头），否则回退首页。 */
    private String sanitizeRedirect(String redirect) {
        if (redirect == null) return baseUrl + "/";
        String trimmed = redirect.trim();
        if (trimmed.startsWith("/") && !trimmed.startsWith("//")) {
            return trimmed;
        }
        return baseUrl + "/";
    }

    private String appendQueryParam(String url, String key, String value) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 校验微信接口 JSON 响应中的字段 */
    private String extractJsonField(String body, String field) {
        if (body == null || body.isBlank()) return null;
        String key = "\"" + field + "\":";
        int i = body.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        int end = body.indexOf("\"", start + 1);
        if (end < 0) return null;
        return body.substring(start + 1, end);
    }
}
