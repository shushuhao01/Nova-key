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

    /**
     * 商品被点击总次数（单一口径）。distribution_click.product_id 在两种推广路径下都写入被点击的商品：
     * 1) 商品推广链接 /p/{code} 被访问（resolve 时 product_id = 该链接商品）；
     * 2) 全店推广链接进店后点击商品（product_id = 被点击商品）。
     * 因此按 product_id 统计即为「该商品推广过程中的点击次数」，与 promotion_link.click_count 解耦。
     */
    long countByProductId(UUID productId);

    /**
     * 区间内商品点击总数（仅 product_id 非空，即真正落到商品上的点击，
     * 不含全店推广链接本身的进店点击）。from/to 由服务层传入非空哨兵值。
     */
    @Query("SELECT COUNT(c) FROM DistributionClick c WHERE c.productId IS NOT NULL " +
            "AND c.createdAt >= :from AND c.createdAt < :to")
    long countProductClicksBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 全店推广链接进店后的商品点击埋点数，按商品聚合（限定分销员；用于前台已推广商品统计） */
    @Query("SELECT c.productId, COUNT(c) FROM DistributionClick c JOIN PromotionLink pl ON pl.id = c.promotionLinkId " +
            "WHERE c.distributorId = :distId AND pl.productId IS NULL AND c.productId IS NOT NULL GROUP BY c.productId")
    List<Object[]> countStoreLinkProductClicksGroupedByProductForDistributor(@Param("distId") UUID distId);

    /** 各推广员首次点击该商品的时间（含商品链接与全店链接点击埋点，取最早；用于初始首次推广时间） */
    @Query("SELECT c.distributorId, MIN(c.createdAt) FROM DistributionClick c WHERE c.productId = :productId GROUP BY c.distributorId")
    List<Object[]> minCreatedAtGroupedByDistributor(@Param("productId") UUID productId);
}
