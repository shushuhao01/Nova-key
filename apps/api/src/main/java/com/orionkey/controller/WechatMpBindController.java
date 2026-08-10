package com.orionkey.controller;

import com.orionkey.common.ApiResponse;
import com.orionkey.constant.ErrorCode;
import com.orionkey.context.RequestContext;
import com.orionkey.entity.User;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.UserRepository;
import com.orionkey.service.WechatMpConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公众号账号绑定：登录用户通过微信 OAuth（snsapi_userinfo 授权，可获取昵称与头像）
 * 获取 openid，写入 User.mp_openid，之后该用户关注公众号即可接收服务通知。
 * <p>公开绑定链接（{@code {站点}/wechat/mp-bind}）可放入服务号菜单或关注自动回复，
 * 客户在微信内点击进入绑定，无需再走分销员提现绑定流程。</p>
 */
@Slf4j
@RestController
@RequestMapping("/wechat-mp")
@RequiredArgsConstructor
public class WechatMpBindController {

    private final WechatMpConfigService mpConfigService;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;

    @Value("${app.base-url:https://noepay.cn}")
    private String baseUrl;

    /** state -> 用户ID（防 CSRF，绑定回调校验；30 分钟内有效，超时自动清理） */
    private static final Map<String, BindState> BIND_STATES = new ConcurrentHashMap<>();

    private record BindState(String userId, long expireAt) {}

    /** 生成公众号 OAuth 授权链接（snsapi_base 静默授权取 openid） */
    @PostMapping("/bind-url")
    public ApiResponse<Map<String, Object>> bindUrl() {
        UUID userId = RequestContext.getUserId();
        if (!mpConfigService.isConfigured()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "公众号尚未配置 AppID/AppSecret");
        }
        String state = UUID.randomUUID().toString().replace("-", "");
        BIND_STATES.put(state, new BindState(userId.toString(), System.currentTimeMillis() + 30 * 60_000L));
        String redirectUri = baseUrl + "/wechat/mp-bind/callback";
        String oauthUrl = "https://open.weixin.qq.com/connect/oauth2/authorize?appid="
                + mpConfigService.getAppid()
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&response_type=code&scope=snsapi_userinfo&state=" + state + "#wechat_redirect";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("oauth_url", oauthUrl);
        m.put("state", state);
        return ApiResponse.success(m);
    }

    /** 用微信授权 code 换 openid，绑定到当前登录用户 */
    @PostMapping("/bind")
    public ApiResponse<Map<String, Object>> bind(@RequestBody Map<String, Object> body) {
        UUID userId = RequestContext.getUserId();
        String code = body.get("code") != null ? body.get("code").toString().trim() : "";
        String state = body.get("state") != null ? body.get("state").toString().trim() : "";
        if (code.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "微信授权失败：缺少 code");
        }
        if (state.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "微信授权失败：缺少 state");
        }
        // 校验 state 归属
        BindState bs = BIND_STATES.get(state);
        if (bs == null || System.currentTimeMillis() > bs.expireAt()) {
            BIND_STATES.remove(state);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "绑定链接已过期，请重新打开绑定链接");
        }
        if (!bs.userId().equals(userId.toString())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "绑定链接与登录账号不匹配，请重新打开绑定链接");
        }
        BIND_STATES.remove(state);
        // code 换 openid（snsapi_userinfo 授权，附带 OAuth access_token 用于拉取用户资料）
        String url = "https://api.weixin.qq.com/sns/oauth2/access_token?appid="
                + mpConfigService.getAppid()
                + "&secret=" + mpConfigService.getAppsecret()
                + "&code=" + code + "&grant_type=authorization_code";
        String openid;
        String oauthToken;
        try {
            String resp = restTemplate.getForObject(url, String.class);
            openid = extractJsonField(resp, "openid");
            oauthToken = extractJsonField(resp, "access_token");
            String errmsg = extractJsonField(resp, "errmsg");
            if (openid == null || openid.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "微信授权失败：" + (errmsg != null && !errmsg.isBlank() ? errmsg : "无效的授权码"));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Wechat MP bind oauth failed, state={}", state, e);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "微信授权失败，请重试");
        }
        // 拉取昵称与头像（snsapi_userinfo 授权后可获取；失败不阻塞绑定）
        String nickname = null;
        String headimgurl = null;
        if (oauthToken != null && !oauthToken.isBlank()) {
            try {
                String infoUrl = "https://api.weixin.qq.com/sns/userinfo?access_token=" + oauthToken
                        + "&openid=" + openid + "&lang=zh_CN";
                String infoBody = restTemplate.getForObject(infoUrl, String.class);
                nickname = extractJsonField(infoBody, "nickname");
                headimgurl = extractJsonField(infoBody, "headimgurl");
            } catch (Exception e) {
                log.warn("Wechat MP bind fetch userinfo failed: {}", e.getMessage());
            }
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        user.setMpOpenid(openid);
        if (nickname != null && !nickname.isBlank()) {
            user.setMpNickname(nickname);
        }
        if (headimgurl != null && !headimgurl.isBlank()) {
            user.setMpAvatar(headimgurl);
        }
        if (user.getMpSubscribe() == null) {
            // 主动绑定说明已关注公众号；后续以关注事件为准
            user.setMpSubscribe("SUBSCRIBED");
            user.setMpSubscribeChangedAt(LocalDateTime.now());
        }
        userRepository.save(user);
        log.info("User {} bound wechat mp openid={}", userId, openid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bound", true);
        m.put("openid", openid);
        return ApiResponse.success(m);
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
