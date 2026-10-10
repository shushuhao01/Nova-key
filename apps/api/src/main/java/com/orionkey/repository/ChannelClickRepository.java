package com.orionkey.repository;

import com.orionkey.entity.ChannelClick;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface ChannelClickRepository extends JpaRepository<ChannelClick, UUID> {

    /** 某渠道下某 IP 的点击次数（独立点击判定） */
    long countByChannelCodeAndIp(String channelCode, String ip);

    /** 区间点击次数 */
    @Query(value = "SELECT COUNT(*) FROM channel_clicks c " +
            "WHERE c.channel_code = :code AND c.created_at >= :from AND c.created_at < :to", nativeQuery = true)
    long countBetween(@Param("code") String code, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 区间独立点击（按 IP 去重，IP 为空不计入独立） */
    @Query(value = "SELECT COUNT(DISTINCT c.ip) FROM channel_clicks c " +
            "WHERE c.channel_code = :code AND c.ip IS NOT NULL " +
            "AND c.created_at >= :from AND c.created_at < :to", nativeQuery = true)
    long countUniqueBetween(@Param("code") String code, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 区间按天点击趋势 [date, clicks] */
    @Query(value = "SELECT CAST(c.created_at AS date), COUNT(*) FROM channel_clicks c " +
            "WHERE c.channel_code = :code AND c.created_at >= :from AND c.created_at < :to " +
            "GROUP BY CAST(c.created_at AS date) ORDER BY CAST(c.created_at AS date)", nativeQuery = true)
    List<Object[]> aggregateDailyBetween(@Param("code") String code,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);
}
