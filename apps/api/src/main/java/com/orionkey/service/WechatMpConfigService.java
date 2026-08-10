package com.orionkey.service;

import java.util.Map;

/**
 * 微信公众号（服务号）配置：AppID/AppSecret/关注二维码/消息模板。
 * 配置后用于分销员扫码绑定微信（snsapi_base 授权取 openid）及引导关注公众号。
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
}
