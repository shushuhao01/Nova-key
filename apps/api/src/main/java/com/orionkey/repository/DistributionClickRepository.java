package com.orionkey.repository;

import com.orionkey.entity.DistributionClick;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

public interface DistributionClickRepository extends JpaRepository<DistributionClick, UUID> {
    long countByPromotionLinkIdAndIp(UUID promotionLinkId, String ip);
    long countByDistributorId(UUID distributorId);

    /** 区间内推广点击总数 */
    @Query("SELECT COUNT(c) FROM DistributionClick c WHERE c.createdAt >= :from AND c.createdAt < :to")
    long countBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 区间内点击总数（from/to 由服务层传入非空哨兵值，避免 null 时间参数 IS NULL 谓词导致 PG 类型推断失败） */
    @Query("SELECT COUNT(c) FROM DistributionClick c WHERE c.createdAt >= :from AND c.createdAt < :to")
    long countByRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
