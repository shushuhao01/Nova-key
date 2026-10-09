package com.orionkey.controller;

import com.orionkey.annotation.LogOperation;
import com.orionkey.common.ApiResponse;
import com.orionkey.service.VisitAnalyticsService;
import com.orionkey.service.VisitConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/visit")
@RequiredArgsConstructor
public class AdminVisitController {

    private final VisitAnalyticsService visitAnalyticsService;
    private final VisitConfigService visitConfigService;

    /** 访问数据综合分析 */
    @GetMapping("/analytics")
    public ApiResponse<?> analytics(@RequestParam(required = false) String range,
                                    @RequestParam(value = "start_date", required = false) String startDate,
                                    @RequestParam(value = "end_date", required = false) String endDate) {
        LocalDate[] r = resolveRange(range, startDate, endDate);
        return ApiResponse.success(visitAnalyticsService.getAnalytics(r[0], r[1]));
    }

    /** 访问明细列表 */
    @GetMapping("/visits")
    public ApiResponse<?> visits(@RequestParam(required = false) String range,
                                 @RequestParam(value = "start_date", required = false) String startDate,
                                 @RequestParam(value = "end_date", required = false) String endDate,
                                 @RequestParam(required = false) String source,
                                 @RequestParam(required = false) String device,
                                 @RequestParam(required = false) String ip,
                                 @RequestParam(required = false) String keyword,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        LocalDate[] r = resolveRange(range, startDate, endDate);
        return ApiResponse.success(visitAnalyticsService.listVisits(r[0], r[1], source, device, ip, keyword, page, pageSize));
    }

    /** 筛选项 */
    @GetMapping("/options")
    public ApiResponse<?> options() {
        return ApiResponse.success(visitAnalyticsService.getOptions());
    }

    /** 读取访问数据配置 */
    @GetMapping("/config")
    public ApiResponse<?> getConfig() {
        return ApiResponse.success(visitConfigService.getConfig());
    }

    /** 保存访问数据配置 */
    @LogOperation(action = "visit.config", targetType = "VISIT", detail = "'保存访问数据配置'")
    @PutMapping("/config")
    public ApiResponse<Void> saveConfig(@RequestBody Map<String, Object> body) {
        visitConfigService.updateConfig(body);
        return ApiResponse.success();
    }

    /** 立即清理过期访问数据 */
    @LogOperation(action = "visit.cleanup", targetType = "VISIT", detail = "'清理过期访问数据'")
    @PostMapping("/cleanup")
    public ApiResponse<?> cleanup() {
        return ApiResponse.success(visitAnalyticsService.cleanup());
    }

    /** 导出访问明细（CSV） */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String range,
                                         @RequestParam(value = "start_date", required = false) String startDate,
                                         @RequestParam(value = "end_date", required = false) String endDate,
                                         @RequestParam(required = false) String source,
                                         @RequestParam(required = false) String device,
                                         @RequestParam(required = false) String ip,
                                         @RequestParam(required = false) String keyword) {
        LocalDate[] r = resolveRange(range, startDate, endDate);
        List<Map<String, Object>> rows = visitAnalyticsService.exportVisits(r[0], r[1], source, device, ip, keyword);

        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("访问时间,IP,国家,省份,城市,运营商,设备,操作系统,浏览器,来源,来源域名,访问路径,访客标识\n");
        for (Map<String, Object> row : rows) {
            appendCsv(sb, row.get("visit_time"));
            appendCsv(sb, row.get("ip"));
            appendCsv(sb, row.get("country"));
            appendCsv(sb, row.get("province"));
            appendCsv(sb, row.get("city"));
            appendCsv(sb, row.get("isp"));
            appendCsv(sb, row.get("device_label"));
            appendCsv(sb, row.get("os"));
            appendCsv(sb, row.get("browser"));
            appendCsv(sb, row.get("source_label"));
            appendCsv(sb, row.get("referer"));
            appendCsv(sb, row.get("path"));
            appendCsvLast(sb, row.get("visitor_id"));
        }

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        String filename = URLEncoder.encode("访问明细_" + LocalDate.now(), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename + ".csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(bytes);
    }

    private static void appendCsv(StringBuilder sb, Object value) {
        sb.append('"').append(csv(value)).append('"').append(',');
    }

    private static void appendCsvLast(StringBuilder sb, Object value) {
        sb.append('"').append(csv(value)).append('"').append('\n');
    }

    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).replace("\"", "\"\"");
    }

    /** 解析日期区间：优先使用 start_date/end_date，其次 range 快捷范围，默认近 7 天 */
    private static LocalDate[] resolveRange(String range, String startDate, String endDate) {
        LocalDate start = parseDate(startDate);
        LocalDate end = parseDate(endDate);
        if (start == null && end == null) {
            LocalDate today = LocalDate.now();
            String r = (range == null || range.isBlank()) ? "7d" : range;
            return switch (r) {
                case "today" -> new LocalDate[]{today, today};
                case "yesterday" -> new LocalDate[]{today.minusDays(1), today.minusDays(1)};
                case "30d" -> new LocalDate[]{today.minusDays(29), today};
                case "90d" -> new LocalDate[]{today.minusDays(89), today};
                default -> new LocalDate[]{today.minusDays(6), today};
            };
        }
        if (start == null) {
            start = end.minusDays(6);
        }
        if (end == null) {
            end = LocalDate.now();
        }
        if (start.isAfter(end)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }
        return new LocalDate[]{start, end};
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String v = value.trim();
            if (v.length() > 10) {
                v = v.substring(0, 10);
            }
            return LocalDate.parse(v);
        } catch (Exception e) {
            return null;
        }
    }
}
