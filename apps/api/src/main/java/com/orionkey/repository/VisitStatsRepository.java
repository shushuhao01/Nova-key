package com.orionkey.repository;

import com.orionkey.entity.VisitStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VisitStatsRepository extends JpaRepository<VisitStats, UUID> {

    Optional<VisitStats> findByVisitDate(LocalDate visitDate);

    /** 区间内累计 PV / UV，返回单行 [pv, uv] */
    @Query("SELECT COALESCE(SUM(v.pv),0), COALESCE(SUM(v.uv),0) FROM VisitStats v WHERE v.visitDate >= :from AND v.visitDate < :to")
    List<Object[]> sumPvUvBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
