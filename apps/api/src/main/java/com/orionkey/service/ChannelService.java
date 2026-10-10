package com.orionkey.service;

import com.orionkey.common.PageResult;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public interface ChannelService {

    /** 公开：解析渠道短链并记录点击，返回渠道信息 */
    Map<String, Object> resolve(String code, String ip, String userAgent, String referer);

    /** 后台：渠道链接列表 */
    PageResult<Map<String, Object>> list(int page, int pageSize, String keyword);

    /** 后台：创建渠道链接 */
    Map<String, Object> create(Map<String, Object> body);

    /** 后台：更新渠道链接 */
    Map<String, Object> update(UUID id, Map<String, Object> body);

    /** 后台：删除渠道链接 */
    void delete(UUID id);

    /** 后台：渠道维度综合分析（引流 / 访问 / 转化） */
    Map<String, Object> analytics(String code, LocalDate from, LocalDate to);

    /** 后台：渠道维度访问明细（分页） */
    PageResult<Map<String, Object>> listVisits(String code, LocalDate from, LocalDate to,
                                               String device, String keyword, int page, int pageSize);
}
