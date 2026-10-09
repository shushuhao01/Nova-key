package com.orionkey.repository;

import com.orionkey.entity.VisitSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VisitSessionRepository extends JpaRepository<VisitSession, UUID> {

    /** 最近一次会话（用于判断是否需要切分） */
    Optional<VisitSession> findFirstByVisitorIdOrderByLastActiveAtDesc(String visitorId);

    /** 区间内会话数 */
    @Query(value = "SELECT COUNT(*) FROM visit_sessions s " +
            "WHERE s.start_time >= :from AND s.start_time < :to", nativeQuery = true)
    long countBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 区间内跳出会话数（仅 1 页） */
    @Query(value = "SELECT COUNT(*) FROM visit_sessions s " +
            "WHERE s.start_time >= :from AND s.start_time < :to AND s.is_bounce = true", nativeQuery = true)
    long countBounceBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 区间内平均会话时长（秒） */
    @Query(value = "SELECT COALESCE(AVG(s.duration_sec), 0) FROM visit_sessions s " +
            "WHERE s.start_time >= :from AND s.start_time < :to", nativeQuery = true)
    double avgDurationBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 区间内平均会话页数 */
    @Query(value = "SELECT COALESCE(AVG(s.page_count), 0) FROM visit_sessions s " +
            "WHERE s.start_time >= :from AND s.start_time < :to", nativeQuery = true)
    double avgPageCountBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 按来源聚合会话 [source, sessions, bounce] */
    @Query(value = "SELECT s.source, COUNT(*), COUNT(*) FILTER (WHERE s.is_bounce = true) FROM visit_sessions s " +
            "WHERE s.start_time >= :from AND s.start_time < :to " +
            "GROUP BY s.source ORDER BY 2 DESC", nativeQuery = true)
    List<Object[]> aggregateBySource(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 删除指定时间之前的会话（数据保留策略） */
    long deleteByStartTimeBefore(LocalDateTime time);
}
