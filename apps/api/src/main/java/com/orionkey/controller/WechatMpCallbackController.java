package com.orionkey.controller;

import com.orionkey.entity.Distributor;
import com.orionkey.entity.User;
import com.orionkey.repository.DistributorRepository;
import com.orionkey.repository.UserRepository;
import com.orionkey.service.WechatMpConfigService;
import com.orionkey.util.WXBizMsgCrypt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * 微信公众号服务器回调（推送接入）。
 * <p>在微信公众平台「设置与开发 → 基本配置 → 服务器配置」中填写：
 * <ul>
 *   <li>URL：本站点自动生成的 {@code {站点域名}/api/wechat-mp/callback}</li>
 *   <li>Token：后台「公众号配置」中随机生成并保存的 Token</li>
 *   <li>EncodingAESKey：后台生成的 43 位密钥（兼容/安全模式）</li>
 *   <li>消息加解密方式：明文 / 兼容 / 安全 三选一</li>
 *   <li>数据格式：XML</li>
 * </ul>
 * GET 用于服务器验证；POST 接收消息与事件（关注/取关），更新用户公众号关注状态。
 */
@Slf4j
@RestController
@RequestMapping("/wechat-mp/callback")
@RequiredArgsConstructor
public class WechatMpCallbackController {

    private final WechatMpConfigService mpConfigService;
    private final UserRepository userRepository;
    private final DistributorRepository distributorRepository;

    /** 微信服务器验证（所有加解密方式通用）：sha1(sort(token, timestamp, nonce)) == signature → 返回 echostr */
    @GetMapping
    public String verify(@RequestParam String signature,
                         @RequestParam String timestamp,
                         @RequestParam String nonce,
                         @RequestParam String echostr) {
        String token = mpConfigService.getToken();
        if (token.isBlank()) {
            log.warn("Wechat MP verify: token not configured");
            return "failure";
        }
        WXBizMsgCrypt crypt = new WXBizMsgCrypt(token, null, null);
        boolean ok = crypt.verifySignature(signature, timestamp, nonce);
        log.info("Wechat MP server verify {}", ok ? "PASS" : "FAIL");
        return ok ? echostr : "failure";
    }

    /** 接收公众号消息与事件（XML）。微信要求 5 秒内响应，统一返回 success */
    @PostMapping(produces = "text/plain; charset=utf-8")
    public String handle(@RequestBody String body,
                         @RequestParam(required = false) String signature,
                         @RequestParam(required = false) String timestamp,
                         @RequestParam(required = false) String nonce,
                         @RequestParam(required = false) String msg_signature) {
        String token = mpConfigService.getToken();
        if (token.isBlank()) {
            log.warn("Wechat MP callback: token not configured");
            return "success";
        }
        String mode = mpConfigService.getEncryptMode();
        String xml = body;
        try {
            String encrypt = extractEncrypt(body);
            if (encrypt != null) {
                // 兼容 / 安全模式：先验签，再决定是否解密
                WXBizMsgCrypt crypt = new WXBizMsgCrypt(token, mpConfigService.getAesKey(), mpConfigService.getAppid());
                if (!crypt.verifyMsgSignature(msg_signature, timestamp, nonce, encrypt)) {
                    log.warn("Wechat MP callback: msg_signature mismatch, ignored");
                    return "success";
                }
                // 兼容模式请求体同时包含明文节点，直接用明文；否则（安全模式）解密
                if (!body.contains("<MsgType>")) {
                    xml = crypt.decrypt(encrypt);
                }
            } else {
                // 明文模式：验证来源
                WXBizMsgCrypt crypt = new WXBizMsgCrypt(token, null, null);
                if (!crypt.verifySignature(signature, timestamp, nonce)) {
                    log.warn("Wechat MP callback: signature mismatch, ignored");
                    return "success";
                }
            }
        } catch (Exception e) {
            log.warn("Wechat MP callback message parse failed: {}", e.getMessage());
            return "success";
        }
        handleXml(xml);
        return "success";
    }

    /** 解析 XML 事件：关注/取关/扫码，更新用户公众号状态 */
    private void handleXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            String msgType = text(doc, "MsgType");
            String event = text(doc, "Event");
            String openid = text(doc, "FromUserName");
            if (openid == null || openid.isBlank()) return;
            if ("event".equalsIgnoreCase(msgType)) {
                if ("subscribe".equalsIgnoreCase(event) || "SCAN".equalsIgnoreCase(event)) {
                    setSubscribe(openid, "SUBSCRIBED");
                } else if ("unsubscribe".equalsIgnoreCase(event)) {
                    setSubscribe(openid, "UNSUBSCRIBED");
                }
            }
        } catch (Exception e) {
            log.warn("Wechat MP event parse failed: {}", e.getMessage());
        }
    }

    /** 按 openid 关联用户（User.mp_openid 或分销员微信绑定），更新关注状态 */
    private void setSubscribe(String openid, String status) {
        User user = userRepository.findByMpOpenid(openid).orElse(null);
        if (user == null) {
            Distributor d = distributorRepository.findByWechatOpenid(openid).orElse(null);
            if (d != null) {
                user = userRepository.findById(d.getUserId()).orElse(null);
            }
        }
        if (user == null) {
            log.info("Wechat MP event: openid {} not linked to any user, status={}", openid, status);
            return;
        }
        // 关注事件：顺带拉取昵称与头像（已关注用户 cgi-bin/user/info 可获取，失败忽略）
        if ("SUBSCRIBED".equals(status) && (user.getMpNickname() == null || user.getMpNickname().isBlank()
                || user.getMpAvatar() == null || user.getMpAvatar().isBlank())) {
            try {
                Map<String, Object> profile = mpConfigService.fetchUserProfile(openid);
                if (profile != null) {
                    Object nick = profile.get("nickname");
                    Object avatar = profile.get("headimgurl");
                    if (nick != null && !nick.toString().isBlank()) user.setMpNickname(nick.toString());
                    if (avatar != null && !avatar.toString().isBlank()) user.setMpAvatar(avatar.toString());
                }
            } catch (Exception e) {
                log.warn("Wechat MP fetch profile on subscribe failed: {}", e.getMessage());
            }
        }
        user.setMpOpenid(openid);
        user.setMpSubscribe(status);
        user.setMpSubscribeChangedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("Wechat MP user {} mp subscribe -> {}", user.getId(), status);
    }

    /** 提取 <Encrypt><![CDATA[xxx]]></Encrypt> 中的密文 */
    private String extractEncrypt(String body) {
        int s = body.indexOf("<Encrypt>");
        if (s < 0) return null;
        int cdata = body.indexOf("<![CDATA[", s);
        if (cdata < 0) return null;
        cdata += 9;
        int e = body.indexOf("]]>", cdata);
        if (e < 0) return null;
        return body.substring(cdata, e);
    }

    private String text(Document doc, String tag) {
        NodeList list = doc.getElementsByTagName(tag);
        return list.getLength() > 0 ? list.item(0).getTextContent() : null;
    }
}
