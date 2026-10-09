package com.orionkey.service;

import java.util.List;
import java.util.Map;

/**
 * 访问数据相关配置（存于 site_configs）。
 */
public interface VisitConfigService {

    /** 是否开启访问采集 */
    boolean trackEnabled();

    /** 是否排除管理员自身访问（默认开启） */
    boolean excludeAdmin();

    /** 是否排除爬虫流量（默认开启） */
    boolean excludeBot();

    /** 访问明细保留天数（默认 90） */
    int retentionDays();

    /** 是否启用 IP 归属地离线解析（默认开启） */
    boolean geolocationEnabled();

    /** ip2region xdb 文件路径 */
    String xdbPath();

    /** IP 白名单（命中则忽略黑名单，强制入库） */
    List<String> ipWhitelist();

    /** IP 黑名单（命中则忽略采集） */
    List<String> ipBlacklist();

    /** 读取全部配置（snake_case key） */
    Map<String, Object> getConfig();

    /** 更新配置 */
    void updateConfig(Map<String, Object> body);
}
