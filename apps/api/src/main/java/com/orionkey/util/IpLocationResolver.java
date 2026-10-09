package com.orionkey.util;

import lombok.extern.slf4j.Slf4j;
import org.lionsoul.ip2region.xdb.Searcher;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;

/**
 * IP 归属地离线解析（基于 ip2region xdb）。
 * <p>
 * 解析库缺失或解析失败时静默降级为 null，绝不影响访问采集主流程。
 * xdb 文件路径可通过配置项 visit_ip_xdb_path 指定，默认 ./data/ip2region.xdb；
 * 若文件不存在则尝试从 classpath（/ip2region.xdb）加载。
 */
@Slf4j
@Component
public class IpLocationResolver {

    private static final String DEFAULT_PATH = "./data/ip2region.xdb";

    /** 解析结果（字段缺失时为 null） */
    public static class Location {
        private final String country;
        private final String province;
        private final String city;
        private final String isp;

        public Location(String country, String province, String city, String isp) {
            this.country = country;
            this.province = province;
            this.city = city;
            this.isp = isp;
        }

        public String getCountry() {
            return country;
        }

        public String getProvince() {
            return province;
        }

        public String getCity() {
            return city;
        }

        public String getIsp() {
            return isp;
        }
    }

    private volatile Searcher searcher;
    private volatile String loadedPath;
    private volatile boolean loadFailed;

    /**
     * 解析 IP 归属地。
     *
     * @param ip     访客 IP
     * @param dbPath xdb 文件路径（可为空，取默认路径）
     * @return 解析结果；无法解析返回 null
     */
    public Location resolve(String ip, String dbPath) {
        if (ip == null || ip.isBlank() || isLocal(ip)) {
            return null;
        }
        String path = (dbPath == null || dbPath.isBlank()) ? DEFAULT_PATH : dbPath;
        Searcher s = searcherFor(path);
        if (s == null) {
            return null;
        }
        try {
            String region = s.search(ip.trim());
            if (region == null || region.isBlank()) {
                return null;
            }
            String[] parts = region.split("\\|", -1);
            String country = clean(parts, 0);
            String province = clean(parts, 2);
            String city = clean(parts, 3);
            String isp = clean(parts, 4);
            if (country == null && province == null && city == null && isp == null) {
                return null;
            }
            return new Location(country, province, city, isp);
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized Searcher searcherFor(String path) {
        if (searcher != null && path.equals(loadedPath)) {
            return searcher;
        }
        if (loadFailed && path.equals(loadedPath)) {
            return null;
        }
        loadedPath = path;
        try {
            File file = new File(path);
            if (file.isFile()) {
                searcher = Searcher.newWithFileOnly(path);
            } else {
                try (InputStream in = getClass().getResourceAsStream("/ip2region.xdb")) {
                    if (in == null) {
                        loadFailed = true;
                        log.info("未找到 ip2region.xdb（路径 {} 与 classpath），IP 归属地解析已跳过", path);
                        return null;
                    }
                    searcher = Searcher.newWithBuffer(in.readAllBytes());
                }
            }
            loadFailed = false;
            return searcher;
        } catch (Exception e) {
            loadFailed = true;
            log.warn("加载 ip2region.xdb 失败，IP 归属地解析已跳过：{}", e.getMessage());
            return null;
        }
    }

    private static String clean(String[] parts, int index) {
        if (parts.length <= index) {
            return null;
        }
        String v = parts[index] == null ? "" : parts[index].trim();
        if (v.isEmpty() || "0".equals(v) || "内网IP".equals(v) || "未知".equals(v)) {
            return null;
        }
        return v;
    }

    private static boolean isLocal(String ip) {
        return "127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)
                || ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.16.")
                || ip.startsWith("172.17.") || ip.startsWith("172.18.") || ip.startsWith("172.19.")
                || ip.startsWith("172.2") || ip.startsWith("172.30.") || ip.startsWith("172.31.")
                || ip.startsWith("169.254.") || ip.startsWith("fe80:");
    }
}
