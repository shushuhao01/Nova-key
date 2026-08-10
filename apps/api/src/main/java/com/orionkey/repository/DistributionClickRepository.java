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

    /** 区间内点击总数（不限制区间，from 可为 null） */
    @Query("SELECT COUNT(c) FROM DistributionClick c WHERE (:from IS NULL OR c.createdAt >= :from) AND (:to IS NULL OR c.createdAt < :to)")
    long countByRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 区间内商品推广链接点击总数（productId 非空=商品推广，不含全店推广；from 可为 null） */
    @Query("SELECT COUNT(c) FROM DistributionClick c WHERE c.productId IS NOT NULL " +
            "AND (:from IS NULL OR c.createdAt >= :from) AND (:to IS NULL OR c.createdAt < :to)")
    long countByRangeProduct(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 区间内商品推广链接点击总数（必填区间，不含全店推广） */
    @Query("SELECT COUNT(c) FROM DistributionClick c WHERE c.productId IS NOT NULL AND c.createdAt >= :from AND c.createdAt < :to")
    long countBetweenProduct(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
