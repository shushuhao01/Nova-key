package com.orionkey.service;

import com.orionkey.common.PageResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface VisitAnalyticsService {

    /** 访问数据综合分析（汇总 / 趋势 / 维度 / 会话 / 漏斗 / 实时） */
    Map<String, Object> getAnalytics(LocalDate from, LocalDate to);

    /** 访问明细列表（分页） */
    PageResult<Map<String, Object>> listVisits(LocalDate from, LocalDate to, String source,
                                               String device, String ip, String keyword,
                                               int page, int pageSize);

    /** 访问明细导出（不分页，最多若干条） */
    List<Map<String, Object>> exportVisits(LocalDate from, LocalDate to, String source,
                                           String device, String ip, String keyword);

    /** 筛选项（来源 / 设备） */
    Map<String, Object> getOptions();

    /** 按保留策略清理过期数据，返回删除条数 */
    Map<String, Object> cleanup();
}
