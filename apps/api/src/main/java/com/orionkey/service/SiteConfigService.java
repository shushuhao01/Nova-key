package com.orionkey.service;

import java.util.List;
import java.util.Map;

public interface SiteConfigService {

    Map<String, Object> getPublicConfig();

    /** 客户配置聚合读取（手机号 / 邮箱双通道），缺失键返回默认值 */
    Map<String, Object> getCustomerConfig();

    /** 读取布尔开关，缺失或非 true 时返回 false */
    boolean isEnabled(String key);

    /** 读取字符串配置，空值返回默认值 */
    String getValue(String key, String defaultValue);

    /** 读取整数配置，缺失或非法时返回默认值 */
    int getIntValue(String key, int defaultValue);

    List<?> getAllConfigs();

    void updateConfigs(List<Map<String, String>> configs);

    void toggleMaintenance(boolean enabled);
}
