package com.orionkey.service;

import java.util.Map;

/**
 * 微信公众号（服务号）配置：AppID/AppSecret/关注二维码/消息模板/服务器配置。
 * 配置后用于分销员扫码绑定微信（snsapi_base 授权取 openid）、接收服务器消息事件（关注/取关）、
 * 以及后续向关注用户推送服务通知。
 */
public interface WechatMpConfigService {

    /** 读取公众号配置（后台编辑回显） */
    Map<String, Object> getConfig();

    /** 保存公众号配置 */
    void updateConfig(Map<String, Object> body);

    /** 测试连接：调用微信 token 接口验证 AppID/AppSecret，返回逐项检测明细 */
    Map<String, Object> testConfig();

    /** 公开的公众号关注信息（仅含关注二维码，不含密钥），供分销中心引导关注 */
    Map<String, Object> getFollowInfo();

    /** 已配置公众号 AppID/AppSecret（用于绑定微信 OAuth） */
    boolean isConfigured();

    /** 公众号 AppID */
    String getAppid();

    /** 公众号 AppSecret */
    String getAppsecret();

    /** 公开绑定链接（放入服务号菜单/关注回复，引导客户绑定账号接收通知） */
    String getBindLink();

    /** 服务器回调 URL（根据当前站点地址自动生成，供微信公众平台「服务器配置」填写） */
    String getServerUrl();

    /** 服务器配置 Token（用于验证消息来自微信服务器） */
    String getToken();

    /** EncodingAESKey（安全/兼容模式解密消息使用） */
    String getAesKey();

    /** 消息加解密方式：plain（明文）/ compatible（兼容）/ safe（安全） */
    String getEncryptMode();

    /** 随机生成 32 位 Token */
    String generateToken();

    /** 随机生成 43 位 EncodingAESKey */
    String generateAesKey();

    /** 获取全局 access_token（带内存缓存，失败返回空串）。需已配置 AppID/AppSecret */
    String getAccessToken();

    /**
     * 获取公众号用户资料（已关注用户可用 cgi-bin/user/info 拉取）。
     * 返回 nickname / headimgurl / subscribe（0 未关注 1 已关注）；失败或未关注返回 null。
     */
    Map<String, Object> fetchUserProfile(String openid);
}
