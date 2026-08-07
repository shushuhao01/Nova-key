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
     *
     * @return { success: boolean, message: 详细原因或成功提示 }
     */
    Map<String, Object> testChannel(UUID id);

    /**
     * 获取渠道的完整配置（含私钥/公钥等敏感字段明文），仅用于管理员在后台查看原值。
     */
    Map<String, Object> getRawConfig(UUID id);
}
