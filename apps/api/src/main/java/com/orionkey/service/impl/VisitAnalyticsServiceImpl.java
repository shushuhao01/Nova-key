package com.orionkey.service.impl;

import com.orionkey.common.PageResult;
import com.orionkey.entity.VisitLog;
import com.orionkey.entity.VisitStats;
import com.orionkey.repository.VisitLogRepository;
import com.orionkey.repository.VisitSessionRepository;
import com.orionkey.repository.VisitStatsRepository;
import com.orionkey.service.VisitAnalyticsService;
import com.orionkey.service.VisitConfigService;
import com.orionkey.util.RefererUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisitAnalyticsServiceImpl implements VisitAnalyticsService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VisitStatsRepository visitStatsRepository;
    private final VisitLogRepository visitLogRepository;
    private final VisitSessionRepository visitSessionRepository;
    private final VisitConfigService visitConfigService;

    @Override
    public Map<String, Object> getAnalytics(LocalDate from, LocalDate to) {
        if (from == null) {
            from = LocalDate.now().minusDays(6);
        }
        if (to == null) {
            to = LocalDate.now();
        }
        if (from.isAfter(to)) {
            LocalDate tmp = from;
            from = to;
            to = tmp;
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;

        Map<String, Object> result = new LinkedHashMap<>();

        // ── 区间信息 ──
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("start", from.format(DATE_FMT));
        range.put("end", to.format(DATE_FMT));
        range.put("days", days);
        result.put("range", range);

        // ── 汇总 ──
        long totalPv = 0;
        long totalUv = 0;
        List<Object[]> sumRows = visitStatsRepository.sumPvUvBetween(from, to.plusDays(1));
        if (!sumRows.isEmpty() && sumRows.get(0) != null) {
            Object[] row = sumRows.get(0);
            totalPv = toLong(row[0]);
            totalUv = toLong(row[1]);
        }
        long distinctIps = visitLogRepository.countDistinctIp(from, to);
        long totalVisitors = visitLogRepository.countDistinctVisitor(from, to);
        long newVisitors = visitLogRepository.countNewVisitors(from, to);
        long returningVisitors = Math.max(0, totalVisitors - newVisitors);

        LocalDate today = LocalDate.now();
        long todayPv = 0;
        long todayUv = 0;
        VisitStats todayStats = visitStatsRepository.findByVisitDate(today).orElse(null);
        if (todayStats != null) {
            todayPv = todayStats.getPv();
            todayUv = todayStats.getUv();
        }

        // 时段分布
        int[] hourPv = new int[24];
        for (Object[] row : visitLogRepository.aggregateByHour(from, to)) {
            int h = row[0] == null ? 0 : ((Number) row[0]).intValue();
            if (h >= 0 && h < 24) {
                hourPv[h] = (int) toLong(row[1]);
            }
        }
        int peakHour = 0;
        for (int i = 1; i < 24; i++) {
            if (hourPv[i] > hourPv[peakHour]) {
                peakHour = i;
            }
        }

        // 会话
        LocalDateTime sessionFrom = from.atStartOfDay();
        LocalDateTime sessionTo = to.plusDays(1).atStartOfDay();
        long sessions = visitSessionRepository.countBetween(sessionFrom, sessionTo);
        long bounces = visitSessionRepository.countBounceBetween(sessionFrom, sessionTo);
        double avgDuration = visitSessionRepository.avgDurationBetween(sessionFrom, sessionTo);
        double avgPageCount = visitSessionRepository.avgPageCountBetween(sessionFrom, sessionTo);
        double bounceRate = sessions > 0 ? round1(bounces * 100.0 / sessions) : 0.0;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("today_pv", todayPv);
        summary.put("today_uv", todayUv);
        summary.put("pv", totalPv);
        summary.put("uv", totalUv);
        summary.put("ips", distinctIps);
        summary.put("visitors", totalVisitors);
        summary.put("new_uv", newVisitors);
        summary.put("returning_uv", returningVisitors);
        summary.put("avg_pv", days > 0 ? round1((double) totalPv / days) : 0.0);
        summary.put("peak_hour", peakHour);
        summary.put("peak_hour_pv", hourPv[peakHour]);
        summary.put("bounce_rate", bounceRate);
        summary.put("avg_duration_sec", (long) Math.round(avgDuration));
        summary.put("avg_page_count", round1(avgPageCount));
        summary.put("sessions", sessions);
        summary.put("pv_per_visitor", totalUv > 0 ? round1((double) totalPv / totalUv) : 0.0);
        result.put("summary", summary);

        // ── 趋势（读 visit_stats，历史连续） ──
        Map<String, long[]> dayMap = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            dayMap.put(d.format(DATE_FMT), new long[]{0, 0});
        }
        for (VisitStats s : visitStatsRepository.findByVisitDateBetweenOrderByVisitDateAsc(from, to)) {
            String key = s.getVisitDate().format(DATE_FMT);
            long[] arr = dayMap.get(key);
            if (arr != null) {
                arr[0] = s.getPv();
                arr[1] = s.getUv();
            }
        }
        List<String> trendDates = new ArrayList<>(dayMap.keySet());
        List<Long> trendPv = new ArrayList<>();
        List<Long> trendUv = new ArrayList<>();
        for (long[] arr : dayMap.values()) {
            trendPv.add(arr[0]);
            trendUv.add(arr[1]);
        }
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("dates", trendDates);
        trend.put("pv", trendPv);
        trend.put("uv", trendUv);
        result.put("trend", trend);

        // ── 时段 ──
        Map<String, Object> hours = new LinkedHashMap<>();
        List<String> hourLabels = new ArrayList<>();
        List<Integer> hourValues = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            hourLabels.add(String.format("%02d:00", i));
            hourValues.add(hourPv[i]);
        }
        hours.put("labels", hourLabels);
        hours.put("pv", hourValues);
        result.put("hours", hours);

        // ── 维度 ──
        result.put("sources", mapSource(visitLogRepository.aggregateBySource(from, to), totalPv));
        result.put("referers", mapSimple(visitLogRepository.aggregateByReferer(from, to), "未知", "name"));
        result.put("devices", mapDevice(visitLogRepository.aggregateByDevice(from, to), totalPv));
        result.put("os", mapSimple(visitLogRepository.aggregateByOs(from, to), "未知", "name"));
        result.put("browsers", mapSimple(visitLogRepository.aggregateByBrowser(from, to), "未知", "name"));
        result.put("regions", mapSimple(visitLogRepository.aggregateByProvince(from, to), "未知", "name"));
        result.put("cities", mapCity(visitLogRepository.aggregateByCity(from, to)));
        result.put("isps", mapSimple(visitLogRepository.aggregateByIsp(from, to), "未知", "name"));
        result.put("pages", mapPage(visitLogRepository.aggregateByPage(from, to)));
        result.put("ips", mapIp(visitLogRepository.aggregateByIp(from, to)));

        // ── 会话维度 ──
        Map<String, Object> sessionStats = new LinkedHashMap<>();
        sessionStats.put("sessions", sessions);
        sessionStats.put("bounces", bounces);
        sessionStats.put("bounce_rate", bounceRate);
        sessionStats.put("avg_duration_sec", (long) Math.round(avgDuration));
        sessionStats.put("avg_page_count", round1(avgPageCount));
        sessionStats.put("sources", mapSessionSource(visitSessionRepository.aggregateBySource(sessionFrom, sessionTo)));
        result.put("sessions", sessionStats);

        // ── 漏斗 ──
        result.put("funnel", buildFunnel(from, to));

        // ── 实时 ──
        Map<String, Object> realtime = new LinkedHashMap<>();
        realtime.put("online", visitLogRepository.countOnlineSince(LocalDateTime.now().minusMinutes(5)));
        result.put("realtime", realtime);

        return result;
    }

    private List<Map<String, Object>> buildFunnel(LocalDate from, LocalDate to) {
        List<Object[]> rows = visitLogRepository.funnel(from, to);
        long[] counts = new long[]{0, 0, 0, 0, 0};
        if (!rows.isEmpty() && rows.get(0) != null) {
            Object[] row = rows.get(0);
            for (int i = 0; i < counts.length && i < row.length; i++) {
                counts[i] = toLong(row[i]);
            }
        }
        String[] labels = {"访问站点", "浏览商品", "加入购物车", "进入结算", "到达支付"};
        List<Map<String, Object>> funnel = new ArrayList<>();
        long base = counts[0];
        for (int i = 0; i < labels.length; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stage", i);
            item.put("label", labels[i]);
            item.put("visitors", counts[i]);
            item.put("rate", base > 0 ? round1(counts[i] * 100.0 / base) : 0.0);
            if (i > 0) {
                item.put("conversion", counts[i - 1] > 0 ? round1(counts[i] * 100.0 / counts[i - 1]) : 0.0);
            } else {
                item.put("conversion", 100.0);
            }
            funnel.add(item);
        }
        return funnel;
    }

    private List<Map<String, Object>> mapSource(List<Object[]> rows, long totalPv) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : rows) {
            String code = row[0] == null ? RefererUtil.SOURCE_DIRECT : String.valueOf(row[0]);
            long pv = toLong(row[1]);
            long uv = toLong(row[2]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("name", RefererUtil.label(code));
            item.put("pv", pv);
            item.put("uv", uv);
            item.put("ratio", totalPv > 0 ? round1(pv * 100.0 / totalPv) : 0.0);
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> mapDevice(List<Object[]> rows, long totalPv) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : rows) {
            String code = row[0] == null ? "unknown" : String.valueOf(row[0]);
            long pv = toLong(row[1]);
            long uv = toLong(row[2]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("name", deviceLabel(code));
            item.put("pv", pv);
            item.put("uv", uv);
            item.put("ratio", totalPv > 0 ? round1(pv * 100.0 / totalPv) : 0.0);
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> mapSimple(List<Object[]> rows, String fallbackName, String nameKey) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : rows) {
            String name = row[0] == null || String.valueOf(row[0]).isBlank() ? fallbackName : String.valueOf(row[0]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(nameKey, name);
            item.put("pv", toLong(row[1]));
            item.put("uv", toLong(row[2]));
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> mapCity(List<Object[]> rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : rows) {
            String province = row[0] == null ? "" : String.valueOf(row[0]);
            String city = row[1] == null ? "" : String.valueOf(row[1]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("province", province.isBlank() ? "未知" : province);
            item.put("name", city.isBlank() ? "未知" : city);
            item.put("pv", toLong(row[2]));
            item.put("uv", toLong(row[3]));
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> mapPage(List<Object[]> rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", row[0] == null ? "/" : String.valueOf(row[0]));
            item.put("pv", toLong(row[1]));
            item.put("uv", toLong(row[2]));
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> mapIp(List<Object[]> rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ip", row[0] == null ? "" : String.valueOf(row[0]));
            item.put("province", row[1] == null ? "" : String.valueOf(row[1]));
            item.put("city", row[2] == null ? "" : String.valueOf(row[2]));
            item.put("isp", row[3] == null ? "" : String.valueOf(row[3]));
            item.put("pv", toLong(row[4]));
            item.put("last_path", row[5] == null ? "" : String.valueOf(row[5]));
            item.put("last_time", formatDateTime(row[6]));
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> mapSessionSource(List<Object[]> rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : rows) {
            String code = row[0] == null ? RefererUtil.SOURCE_DIRECT : String.valueOf(row[0]);
            long total = toLong(row[1]);
            long bounce = toLong(row[2]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("name", RefererUtil.label(code));
            item.put("sessions", total);
            item.put("bounce_rate", total > 0 ? round1(bounce * 100.0 / total) : 0.0);
            list.add(item);
        }
        return list;
    }

    @Override
    public PageResult<Map<String, Object>> listVisits(LocalDate from, LocalDate to, String source,
                                                      String device, String ip, String keyword,
                                                      int page, int pageSize) {
        if (from == null) {
            from = LocalDate.now().minusDays(6);
        }
        if (to == null) {
            to = LocalDate.now();
        }
        if (page < 1) {
            page = 1;
        }
        if (pageSize < 1) {
            pageSize = 20;
        }
        if (pageSize > 200) {
            pageSize = 200;
        }
        Page<VisitLog> result = visitLogRepository.findByFilters(from, to,
                blankToNull(source), blankToNull(device), blankToNull(ip), blankToNull(keyword),
                PageRequest.of(page - 1, pageSize));
        List<Map<String, Object>> list = new ArrayList<>();
        for (VisitLog l : result.getContent()) {
            list.add(toRow(l));
        }
        return PageResult.of(result, list);
    }

    @Override
    public List<Map<String, Object>> exportVisits(LocalDate from, LocalDate to, String source,
                                                  String device, String ip, String keyword) {
        if (from == null) {
            from = LocalDate.now().minusDays(6);
        }
        if (to == null) {
            to = LocalDate.now();
        }
        Page<VisitLog> result = visitLogRepository.findByFilters(from, to,
                blankToNull(source), blankToNull(device), blankToNull(ip), blankToNull(keyword),
                PageRequest.of(0, 10000));
        List<Map<String, Object>> list = new ArrayList<>();
        for (VisitLog l : result.getContent()) {
            list.add(toRow(l));
        }
        return list;
    }

    private Map<String, Object> toRow(VisitLog l) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", l.getId());
        item.put("visit_time", formatDateTime(l.getCreatedAt()));
        item.put("ip", l.getIp());
        item.put("country", l.getCountry());
        item.put("province", l.getProvince());
        item.put("city", l.getCity());
        item.put("isp", l.getIsp());
        item.put("device", l.getDevice());
        item.put("device_label", deviceLabel(l.getDevice()));
        item.put("os", l.getOs());
        item.put("browser", l.getBrowser());
        item.put("source", l.getSource());
        item.put("source_label", RefererUtil.label(l.getSource()));
        item.put("referer", l.getReferer());
        item.put("path", l.getPath());
        item.put("visitor_id", l.getVisitorId());
        return item;
    }

    @Override
    public Map<String, Object> getOptions() {
        Map<String, Object> options = new LinkedHashMap<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        for (String code : new String[]{RefererUtil.SOURCE_DIRECT, RefererUtil.SOURCE_SEARCH,
                RefererUtil.SOURCE_SOCIAL, RefererUtil.SOURCE_EXTERNAL}) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", code);
            item.put("label", RefererUtil.label(code));
            sources.add(item);
        }
        List<Map<String, Object>> devices = new ArrayList<>();
        for (String code : new String[]{"desktop", "mobile", "tablet", "bot", "unknown"}) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", code);
            item.put("label", deviceLabel(code));
            devices.add(item);
        }
        options.put("sources", sources);
        options.put("devices", devices);
        options.put("retention_days", visitConfigService.retentionDays());
        return options;
    }

    @Override
    @Transactional
    public Map<String, Object> cleanup() {
        int retentionDays = visitConfigService.retentionDays();
        LocalDate cutoffDate = LocalDate.now().minusDays(retentionDays);
        long deletedLogs = visitLogRepository.deleteByVisitDateBefore(cutoffDate);
        long deletedSessions = visitSessionRepository.deleteByStartTimeBefore(cutoffDate.atStartOfDay());
        log.info("访问数据清理完成：明细 {} 条，会话 {} 条（保留 {} 天）", deletedLogs, deletedSessions, retentionDays);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted_logs", deletedLogs);
        result.put("deleted_sessions", deletedSessions);
        result.put("retention_days", retentionDays);
        return result;
    }

    private static String deviceLabel(String code) {
        if (code == null) {
            return "未知";
        }
        return switch (code) {
            case "desktop" -> "桌面端";
            case "mobile" -> "移动端";
            case "tablet" -> "平板";
            case "bot" -> "爬虫 / 机器人";
            default -> "未知";
        };
    }

    private static String formatDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dt) {
            return dt.format(TIME_FMT);
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().format(TIME_FMT);
        }
        return String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
