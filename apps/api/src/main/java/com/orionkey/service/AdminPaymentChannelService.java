package com.orionkey.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AdminPaymentChannelService {

    List<?> listChannels();

    void createChannel(Map<String, Object> request);

    void updateChannel(UUID id, Map<String, Object> request);

    void deleteChannel(UUID id);

    /**
     * 测试支付渠道配置与支付平台（微信/支付宝/易支付/USDT）的连通性。
     * <p>
     * 原生微信/支付宝逐项输出检测清单，与 CRM 项目支付配置的测试连接一致：
     * 微信：AppID / 商户号 / API密钥 / 证书 / 连接测试；
     * 支付宝：AppID / 商家私钥 / 支付宝公钥 / 签名类型 / 连接测试。
     *
     * @return { success: boolean, message: 汇总提示, items: [{name, status, message}] 逐项清单 }
     */
    Map<String, Object> testChannel(UUID id);

    /**
     * 获取渠道的完整配置（含私钥/公钥等敏感字段明文），仅用于管理员在后台查看原值。
     */
    Map<String, Object> getRawConfig(UUID id);
}
