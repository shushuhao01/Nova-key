package com.orionkey.repository;

import com.orionkey.entity.DistributionClick;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
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

    /** 商品推广点击按推广员分组（商品推广链接产生的点击；全店推广链接 product_id 为 null 不计入） */
    @Query("SELECT c.distributorId, COUNT(c) FROM DistributionClick c WHERE c.productId = :productId GROUP BY c.distributorId")
    List<Object[]> countClicksByProductGroupedByDistributor(@Param("productId") UUID productId);

    /** 分销员各商品点击数（DistributionClick 按商品聚合：含商品推广链接点击 + 全店推广链接进店后点击商品的埋点） */
    @Query("SELECT c.productId, COUNT(c) FROM DistributionClick c WHERE c.distributorId = :distId AND c.productId IS NOT NULL GROUP BY c.productId")
    List<Object[]> countClicksGroupedByProductForDistributor(@Param("distId") UUID distId);
}
