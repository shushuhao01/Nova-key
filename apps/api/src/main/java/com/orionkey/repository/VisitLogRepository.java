package com.orionkey.repository;

import com.orionkey.entity.VisitLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface VisitLogRepository extends JpaRepository<VisitLog, UUID> {

    /** 明细列表（带筛选，分页） */
    @Query(value = "SELECT * FROM visit_logs l WHERE " +
            "l.visit_date >= CAST(:from AS date) AND l.visit_date <= CAST(:to AS date) " +
            "AND (CAST(:source AS text) IS NULL OR l.source = CAST(:source AS text)) " +
            "AND (CAST(:device AS text) IS NULL OR l.device = CAST(:device AS text)) " +
            "AND (CAST(:ip AS text) IS NULL OR l.ip = CAST(:ip AS text)) " +
            "AND (CAST(:keyword AS text) IS NULL OR l.path LIKE '%' || CAST(:keyword AS text) || '%' " +
            "     OR l.ip LIKE '%' || CAST(:keyword AS text) || '%') " +
            "ORDER BY l.created_at DESC",
            countQuery = "SELECT COUNT(*) FROM visit_logs l WHERE " +
            "l.visit_date >= CAST(:from AS date) AND l.visit_date <= CAST(:to AS date) " +
            "AND (CAST(:source AS text) IS NULL OR l.source = CAST(:source AS text)) " +
            "AND (CAST(:device AS text) IS NULL OR l.device = CAST(:device AS text)) " +
            "AND (CAST(:ip AS text) IS NULL OR l.ip = CAST(:ip AS text)) " +
            "AND (CAST(:keyword AS text) IS NULL OR l.path LIKE '%' || CAST(:keyword AS text) || '%' " +
            "     OR l.ip LIKE '%' || CAST(:keyword AS text) || '%')",
            nativeQuery = true)
    Page<VisitLog> findByFilters(@Param("from") LocalDate from,
                                 @Param("to") LocalDate to,
                                 @Param("source") String source,
                                 @Param("device") String device,
                                 @Param("ip") String ip,
                                 @Param("keyword") String keyword,
                                 Pageable pageable);

    /** 区间内去重 IP 数 */
    @Query(value = "SELECT COUNT(DISTINCT l.ip) FROM visit_logs l " +
            "WHERE l.visit_date >= :from AND l.visit_date <= :to", nativeQuery = true)
    long countDistinctIp(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 实时在线（近 N 分钟去重访客） */
    @Query(value = "SELECT COUNT(DISTINCT l.visitor_id) FROM visit_logs l WHERE l.created_at >= :since",
            nativeQuery = true)
    long countOnlineSince(@Param("since") LocalDateTime since);

    /** 新增访客数（区间内首访） */
    @Query(value = "SELECT COUNT(*) FROM (" +
            "SELECT l.visitor_id FROM visit_logs l " +
            "WHERE l.visit_date >= :from AND l.visit_date <= :to " +
            "GROUP BY l.visitor_id HAVING MIN(l.visit_date) >= :from) t", nativeQuery = true)
    long countNewVisitors(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 区间内去重访客总数 */
    @Query(value = "SELECT COUNT(DISTINCT l.visitor_id) FROM visit_logs l " +
            "WHERE l.visit_date >= :from AND l.visit_date <= :to", nativeQuery = true)
    long countDistinctVisitor(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 时段分布 [hour, pv] */
    @Query(value = "SELECT l.visit_hour, COUNT(*) FROM visit_logs l " +
            "WHERE l.visit_date >= :from AND l.visit_date <= :to " +
            "GROUP BY l.visit_hour ORDER BY l.visit_hour", nativeQuery = true)
    List<Object[]> aggregateByHour(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 按来源聚合 [source, pv, uv] */
    @Query(value = "SELECT l.source, COUNT(*), COUNT(DISTINCT l.visitor_id) FROM visit_logs l " +
            "WHERE l.visit_date >= :from AND l.visit_date <= :to " +
            "GROUP BY l.source ORDER BY 2 DESC", nativeQuery = true)
    List<Object[]> aggregateBySource(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 按来源域名聚合 [refererHost, pv, uv] */
    @Query(value = "SELECT l.referer, COUNT(*), COUNT(DISTINCT l.visitor_id) FROM visit_logs l " +
            "WHERE l.visit_date >= :from AND l.visit_date <= :to AND l.referer IS NOT NULL AND l.referer <> '' " +
            "GROUP BY l.referer ORDER BY 2 DESC LIMIT 50", nativeQuery = true)
    List<Object[]> aggregateByReferer(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 按设备聚合 [device, pv, uv] */
    @Query(value = "SELECT l.device, COUNT(*), COUNT(DISTINCT l.visitor_id) FROM visit_logs l " +
            "WHERE l.visit_date >= :from AND l.visit_date <= :to " +
            "GROUP BY l.device ORDER BY 2 DESC", nativeQuery = true)
    List<Object[]> aggregateByDevice(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 按系统聚合 [os, pv, uv] */
    @Query(value = "SELECT l.os, COUNT(*), COUNT(DISTINCT l.visitor_id) FROM visit_logs l " +
            "WHERE l.visit_date >= :from AND l.visit_date <= :to " +
            "GROUP BY l.os ORDER BY 2 DESC", nativeQuery = true)
    List<Object[]> aggregateByOs(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 按浏览器聚合 [browser, pv, uv] */
    @Query(value = "SELECT l.browser, COUNT(*), COUNT(DISTINCT l.visitor_id) FROM visit_logs l " +
            "WHERE l.visit_date >= :from AND l.visit_date <= :to " +
            "GROUP BY l.browser ORDER BY 2 DESC", nativeQuery = true)
    List<Object[]> aggregateByBrowser(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 按省份聚合 [province, pv, uv] */
    @Query(value = "SELECT l.province, COUNT(*), COUNT(DISTINCT l.visitor_id) FROM visit_logs l " +
            "WHERE l.visit_date >= :from AND l.visit_date <= :to " +
            "GROUP BY l.province ORDER BY 2 DESC", nativeQuery = true)
    List<Object[]> aggregateByProvince(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 按城市聚合 [province, city, pv, uv] */
    @Query(value = "SELECT l.province, l.city, COUNT(*), COUNT(DISTINCT l.visitor_id) FROM visit_logs l " +
            "WHERE l.visit_date >= :from AND l.visit_date <= :to " +
            "GROUP BY l.province, l.city ORDER BY 3 DESC LIMIT 50", nativeQuery = true)
    List<Object[]> aggregateByCity(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 按运营商聚合 [isp, pv, uv] */
    @Query(value = "SELECT l.isp, COUNT(*), COUNT(DISTINCT l.visitor_id) FROM visit_logs l " +
            "WHERE l.visit_date >= :from AND l.visit_date <= :to " +
            "GROUP BY l.isp ORDER BY 2 DESC", nativeQuery = true)
    List<Object[]> aggregateByIsp(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 按页面聚合 [path, pv, uv] */
    @Query(value = "SELECT l.path, COUNT(*), COUNT(DISTINCT l.visitor_id) FROM visit_logs l " +
            "WHERE l.visit_date >= :from AND l.visit_date <= :to " +
            "GROUP BY l.path ORDER BY 2 DESC LIMIT 50", nativeQuery = true)
    List<Object[]> aggregateByPage(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** IP 明细 [ip, province, city, isp, pv, lastPath, lastTime] */
    @Query(value = "SELECT sub.ip, sub.province, sub.city, sub.isp, sub.pv, sub.last_path, sub.last_time FROM (" +
            "SELECT DISTINCT ON (l.ip) l.ip AS ip, l.province AS province, l.city AS city, l.isp AS isp, " +
            "COUNT(*) OVER (PARTITION BY l.ip) AS pv, l.path AS last_path, l.created_at AS last_time " +
            "FROM visit_logs l WHERE l.visit_date >= :from AND l.visit_date <= :to " +
            "ORDER BY l.ip, l.created_at DESC) sub ORDER BY sub.pv DESC LIMIT 100", nativeQuery = true)
    List<Object[]> aggregateByIp(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 漏斗各阶段去重访客数 [all, product, cart, checkout, pay]，均为该阶段真实人数。
     * 注意：各阶段相互独立，因存在「跳过购物车直接购买」，下层人数可能大于上层，
     * 图形侧由前端做宽度单调钳制，此处不做累计合并。
     */
    @Query(value = "SELECT " +
            "COUNT(DISTINCT l.visitor_id), " +
            "COUNT(DISTINCT CASE WHEN l.path LIKE '/product%' THEN l.visitor_id END), " +
            "COUNT(DISTINCT CASE WHEN l.path LIKE '/cart%' THEN l.visitor_id END), " +
            "COUNT(DISTINCT CASE WHEN l.path LIKE '/checkout%' THEN l.visitor_id END), " +
            "COUNT(DISTINCT CASE WHEN l.path LIKE '/pay%' THEN l.visitor_id END) " +
            "FROM visit_logs l WHERE l.visit_date >= :from AND l.visit_date <= :to", nativeQuery = true)
    List<Object[]> funnel(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 删除指定日期之前的明细（数据保留策略） */
    long deleteByVisitDateBefore(LocalDate date);
}
