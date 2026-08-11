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

    /** 全店推广链接进店后的商品点击埋点数，按分销员聚合（仅统计全店链接：所属链接 productId 为 null 的点击，不含商品链接点击以免与 PromotionLink.clickCount 重复） */
    @Query("SELECT c.distributorId, COUNT(c) FROM DistributionClick c JOIN PromotionLink pl ON pl.id = c.promotionLinkId " +
            "WHERE pl.productId IS NULL AND c.productId = :productId GROUP BY c.distributorId")
    List<Object[]> countStoreLinkProductClicksGroupedByDistributor(@Param("productId") UUID productId);

    /** 全店推广链接进店后的商品点击埋点数，按商品聚合（限定分销员；用于前台已推广商品统计） */
    @Query("SELECT c.productId, COUNT(c) FROM DistributionClick c JOIN PromotionLink pl ON pl.id = c.promotionLinkId " +
            "WHERE c.distributorId = :distId AND pl.productId IS NULL AND c.productId IS NOT NULL GROUP BY c.productId")
    List<Object[]> countStoreLinkProductClicksGroupedByProductForDistributor(@Param("distId") UUID distId);
}
