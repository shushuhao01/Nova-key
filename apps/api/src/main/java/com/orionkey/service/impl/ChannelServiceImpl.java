package com.orionkey.service.impl;

import com.orionkey.common.PageResult;
import com.orionkey.constant.ErrorCode;
import com.orionkey.entity.ChannelClick;
import com.orionkey.entity.ChannelLink;
import com.orionkey.entity.VisitLog;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.ChannelClickRepository;
import com.orionkey.repository.ChannelLinkRepository;
import com.orionkey.repository.OrderRepository;
import com.orionkey.repository.VisitLogRepository;
import com.orionkey.repository.VisitSessionRepository;
import com.orionkey.service.ChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelServiceImpl implements ChannelService {

    private static final String CODE_CHARS = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ChannelLinkRepository channelLinkRepository;
    private final ChannelClickRepository channelClickRepository;
    private final VisitLogRepository visitLogRepository;
    private final VisitSessionRepository visitSessionRepository;
    private final OrderRepository orderRepository;

    @Value("${app.base-url:https://noepay.cn}")
    private String baseUrl;

    // ══════════════════════ 公开解析 ══════════════════════

    @Override
    @Transactional
    public Map<String, Object> resolve(String code, String ip, String userAgent, String referer) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "渠道编码不能为空");
        }
        String normalized = code.trim().toLowerCase();
        ChannelLink link = channelLinkRepository.findByCode(normalized)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "渠道链接不存在"));
        if (!link.isEnabled()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "渠道链接已停用");
        }

        try {
            String clickIp = truncate(ip, 64);
            ChannelClick click = new ChannelClick();
            click.setChannelCode(link.getCode());
            click.setIp(clickIp);
            click.setUserAgent(truncate(userAgent, 512));
            click.setReferer(truncate(referer, 512));
            channelClickRepository.save(click);

            link.setClickCount(link.getClickCount() + 1);
            if (clickIp != null) {
                // 独立点击按 IP 去重：该 IP 在此渠道的首次点击时才计数
                long ipClicks = channelClickRepository.countByChannelCodeAndIp(link.getCode(), clickIp);
                if (ipClicks <= 1) {
                    link.setUniqueClickCount(link.getUniqueClickCount() + 1);
                }
            }
            channelLinkRepository.save(link);
        } catch (Exception e) {
            // 埋点失败不影响跳转
            log.warn("记录渠道点击失败：{}", e.getMessage());
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channel_code", link.getCode());
        m.put("code", link.getCode());
        m.put("name", link.getName());
        m.put("target_path", (link.getTargetPath() == null || link.getTargetPath().isBlank()) ? "/" : link.getTargetPath());
        return m;
    }

    // ══════════════════════ 后台管理 ══════════════════════

    @Override
    public PageResult<Map<String, Object>> list(int page, int pageSize, String keyword) {
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 20;
        if (pageSize > 200) pageSize = 200;
        Page<ChannelLink> result = channelLinkRepository.findAdminList(blankToNull(keyword),
                PageRequest.of(page - 1, pageSize));
        List<Map<String, Object>> list = new ArrayList<>();
        for (ChannelLink l : result.getContent()) {
            list.add(toRow(l));
        }
        return PageResult.of(result, list);
    }

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        String name = str(body.get("name"));
        String code = str(body.get("code"));
        if (code == null) {
            code = generateUniqueCode();
        } else {
            code = code.trim().toLowerCase();
            if (!code.matches("^[a-z0-9_-]{1,32}$")) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "渠道编码仅支持字母、数字、下划线和短横线");
            }
            if (channelLinkRepository.existsByCode(code)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "渠道编码已存在");
            }
        }
        ChannelLink link = new ChannelLink();
        link.setCode(code);
        link.setName(name);
        link.setChannel(str(body.get("channel")));
        link.setTargetPath(normalizeTargetPath(str(body.get("target_path"))));
        link.setRemark(str(body.get("remark")));
        link.setEnabled(body.get("enabled") == null || Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
        link.setClickCount(0L);
        link.setUniqueClickCount(0L);
        channelLinkRepository.save(link);
        return toRow(link);
    }

    @Override
    @Transactional
    public Map<String, Object> update(UUID id, Map<String, Object> body) {
        ChannelLink link = channelLinkRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "渠道链接不存在"));
        if (body.containsKey("name")) {
            link.setName(str(body.get("name")));
        }
        if (body.containsKey("channel")) {
            link.setChannel(str(body.get("channel")));
        }
        if (body.containsKey("target_path")) {
            link.setTargetPath(normalizeTargetPath(str(body.get("target_path"))));
        }
        if (body.containsKey("remark")) {
            link.setRemark(str(body.get("remark")));
        }
        if (body.containsKey("enabled")) {
            link.setEnabled(Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
        }
        channelLinkRepository.save(link);
        return toRow(link);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        if (!channelLinkRepository.existsById(id)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "渠道链接不存在");
        }
        channelLinkRepository.deleteById(id);
    }

    // ══════════════════════ 渠道分析 ══════════════════════

    @Override
    public Map<String, Object> analytics(String code, LocalDate from, LocalDate to) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "渠道编码不能为空");
        }
        String channel = code.trim().toLowerCase();
        if (from == null) from = LocalDate.now().minusDays(6);
        if (to == null) to = LocalDate.now();
        if (from.isAfter(to)) {
            LocalDate tmp = from;
            from = to;
            to = tmp;
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;

        ChannelLink link = channelLinkRepository.findByCode(channel).orElse(null);

        LocalDateTime dtFrom = from.atStartOfDay();
        LocalDateTime dtTo = to.plusDays(1).atStartOfDay();

        // 点击
        long clicks = channelClickRepository.countBetween(channel, dtFrom, dtTo);
        long uniqueClicks = channelClickRepository.countUniqueBetween(channel, dtFrom, dtTo);

        // 访问
        long pv = visitLogRepository.countChannelPv(channel, from, to);
        long uv = visitLogRepository.countChannelDistinctVisitor(channel, from, to);
        long ips = visitLogRepository.countChannelDistinctIp(channel, from, to);
        long newUv = visitLogRepository.countChannelNewVisitors(channel, from, to);
        long sessions = visitSessionRepository.countChannelBetween(channel, dtFrom, dtTo);
        long bounces = visitSessionRepository.countChannelBounceBetween(channel, dtFrom, dtTo);
        double avgDuration = visitSessionRepository.avgChannelDurationBetween(channel, dtFrom, dtTo);
        double avgPageCount = visitSessionRepository.avgChannelPageCountBetween(channel, dtFrom, dtTo);
        double bounceRate = sessions > 0 ? round1(bounces * 100.0 / sessions) : 0.0;

        // 转化
        long orders = orderRepository.countChannelOrdersBetween(channel, dtFrom, dtTo);
        long paidOrders = orderRepository.countChannelPaidBetween(channel, dtFrom, dtTo);
        BigDecimal sales = orderRepository.sumChannelSalesBetween(channel, dtFrom, dtTo);
        if (sales == null) sales = BigDecimal.ZERO;
        BigDecimal aov = paidOrders > 0
                ? sales.divide(BigDecimal.valueOf(paidOrders), 2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        double conversionRate = uv > 0 ? round1(paidOrders * 100.0 / uv) : 0.0;

        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> range = new LinkedHashMap<>();
        range.put("start", from.format(DATE_FMT));
        range.put("end", to.format(DATE_FMT));
        range.put("days", days);
        result.put("range", range);

        Map<String, Object> channelInfo = new LinkedHashMap<>();
        channelInfo.put("code", channel);
        if (link != null) {
            channelInfo.put("id", link.getId());
            channelInfo.put("name", link.getName());
            channelInfo.put("channel", link.getChannel());
            channelInfo.put("target_path", link.getTargetPath());
            channelInfo.put("remark", link.getRemark());
            channelInfo.put("enabled", link.isEnabled());
            channelInfo.put("url", buildChannelUrl(channel));
        } else {
            channelInfo.put("name", channel);
            channelInfo.put("url", buildChannelUrl(channel));
        }
        result.put("channel", channelInfo);

        Map<String, Object> traffic = new LinkedHashMap<>();
        traffic.put("clicks", clicks);
        traffic.put("unique_clicks", uniqueClicks);
        traffic.put("pv", pv);
        traffic.put("uv", uv);
        traffic.put("ips", ips);
        traffic.put("new_uv", newUv);
        traffic.put("sessions", sessions);
        traffic.put("bounces", bounces);
        traffic.put("bounce_rate", bounceRate);
        traffic.put("avg_duration_sec", (long) Math.round(avgDuration));
        traffic.put("avg_page_count", round1(avgPageCount));
        traffic.put("pv_per_visitor", uv > 0 ? round1((double) pv / uv) : 0.0);
        result.put("traffic", traffic);

        Map<String, Object> conversion = new LinkedHashMap<>();
        conversion.put("orders", orders);
        conversion.put("paid_orders", paidOrders);
        conversion.put("sales", sales);
        conversion.put("aov", aov);
        conversion.put("conversion_rate", conversionRate);
        result.put("conversion", conversion);

        // 趋势（pv / uv / 点击）
        Map<String, long[]> dayMap = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            dayMap.put(d.format(DATE_FMT), new long[]{0, 0, 0});
        }
        for (Object[] row : visitLogRepository.aggregateChannelDaily(channel, from, to)) {
            String key = toDateString(row[0]);
            long[] arr = key != null ? dayMap.get(key) : null;
            if (arr != null) {
                arr[0] = toLong(row[1]);
                arr[1] = toLong(row[2]);
            }
        }
        for (Object[] row : channelClickRepository.aggregateDailyBetween(channel, dtFrom, dtTo)) {
            String key = toDateString(row[0]);
            long[] arr = key != null ? dayMap.get(key) : null;
            if (arr != null) {
                arr[2] = toLong(row[1]);
            }
        }
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("dates", new ArrayList<>(dayMap.keySet()));
        List<Long> tPv = new ArrayList<>();
        List<Long> tUv = new ArrayList<>();
        List<Long> tClicks = new ArrayList<>();
        for (long[] arr : dayMap.values()) {
            tPv.add(arr[0]);
            tUv.add(arr[1]);
            tClicks.add(arr[2]);
        }
        trend.put("pv", tPv);
        trend.put("uv", tUv);
        trend.put("clicks", tClicks);
        result.put("trend", trend);

        // 时段
        int[] hourPv = new int[24];
        for (Object[] row : visitLogRepository.aggregateChannelByHour(channel, from, to)) {
            int h = row[0] == null ? 0 : ((Number) row[0]).intValue();
            if (h >= 0 && h < 24) {
                hourPv[h] = (int) toLong(row[1]);
            }
        }
        List<String> hourLabels = new ArrayList<>();
        List<Integer> hourValues = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            hourLabels.add(String.format("%02d:00", i));
            hourValues.add(hourPv[i]);
        }
        Map<String, Object> hours = new LinkedHashMap<>();
        hours.put("labels", hourLabels);
        hours.put("pv", hourValues);
        result.put("hours", hours);

        // 维度
        result.put("devices", mapDevice(visitLogRepository.aggregateChannelByDevice(channel, from, to), pv));
        result.put("regions", mapSimple(visitLogRepository.aggregateChannelByProvince(channel, from, to)));
        result.put("pages", mapPage(visitLogRepository.aggregateChannelByPage(channel, from, to)));

        // 漏斗
        result.put("funnel", buildFunnel(channel, from, to));

        return result;
    }

    @Override
    public PageResult<Map<String, Object>> listVisits(String code, LocalDate from, LocalDate to,
                                                      String device, String keyword, int page, int pageSize) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "渠道编码不能为空");
        }
        String channel = code.trim().toLowerCase();
        if (from == null) from = LocalDate.now().minusDays(6);
        if (to == null) to = LocalDate.now();
        if (from.isAfter(to)) {
            LocalDate tmp = from;
            from = to;
            to = tmp;
        }
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 20;
        if (pageSize > 200) pageSize = 200;
        Page<VisitLog> result = visitLogRepository.findByChannelFilters(channel, from, to,
                blankToNull(device), blankToNull(keyword), PageRequest.of(page - 1, pageSize));
        List<Map<String, Object>> list = new ArrayList<>();
        for (VisitLog l : result.getContent()) {
            list.add(toVisitRow(l));
        }
        return PageResult.of(result, list);
    }

    private List<Map<String, Object>> buildFunnel(String channel, LocalDate from, LocalDate to) {
        List<Object[]> rows = visitLogRepository.funnelByChannel(channel, from, to);
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
            item.put("conversion", i > 0
                    ? (counts[i - 1] > 0 ? round1(counts[i] * 100.0 / counts[i - 1]) : 0.0)
                    : 100.0);
            funnel.add(item);
        }
        return funnel;
    }

    // ══════════════════════ 映射工具 ══════════════════════

    private Map<String, Object> toRow(ChannelLink l) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", l.getId());
        item.put("code", l.getCode());
        item.put("name", l.getName());
        item.put("channel", l.getChannel());
        item.put("target_path", l.getTargetPath());
        item.put("remark", l.getRemark());
        item.put("enabled", l.isEnabled());
        item.put("click_count", l.getClickCount());
        item.put("unique_click_count", l.getUniqueClickCount());
        item.put("url", buildChannelUrl(l.getCode()));
        item.put("created_at", l.getCreatedAt() != null ? l.getCreatedAt().format(TIME_FMT) : null);
        return item;
    }

    private Map<String, Object> toVisitRow(VisitLog l) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", l.getId());
        item.put("visit_time", l.getCreatedAt() != null ? l.getCreatedAt().format(TIME_FMT) : null);
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
        item.put("referer", l.getReferer());
        item.put("path", l.getPath());
        item.put("visitor_id", l.getVisitorId());
        return item;
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

    private List<Map<String, Object>> mapSimple(List<Object[]> rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : rows) {
            String name = row[0] == null || String.valueOf(row[0]).isBlank() ? "未知" : String.valueOf(row[0]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("pv", toLong(row[1]));
            item.put("uv", toLong(row[2]));
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

    private String buildChannelUrl(String code) {
        String base = baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://noepay.cn";
        return base + (base.endsWith("/") ? "" : "/") + "c/" + code;
    }

    private String generateUniqueCode() {
        for (int i = 0; i < 20; i++) {
            String code = randomCode(6);
            if (!channelLinkRepository.existsByCode(code)) {
                return code;
            }
        }
        return randomCode(6);
    }

    private static String randomCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(CODE_CHARS.charAt(rnd.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private static String normalizeTargetPath(String value) {
        if (value == null || value.isBlank()) {
            return "/";
        }
        String v = value.trim();
        if (!v.startsWith("/")) {
            v = "/" + v;
        }
        return v.length() <= 255 ? v : v.substring(0, 255);
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

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String v = String.valueOf(value).trim();
        return v.isEmpty() ? null : v;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }

    private static String toDateString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().format(DATE_FMT);
        }
        if (value instanceof LocalDate d) {
            return d.format(DATE_FMT);
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate().format(DATE_FMT);
        }
        String s = String.valueOf(value);
        return s.length() > 10 ? s.substring(0, 10) : s;
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
