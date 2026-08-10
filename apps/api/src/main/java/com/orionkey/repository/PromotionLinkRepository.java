package com.orionkey.repository;

import com.orionkey.entity.PromotionLink;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PromotionLinkRepository extends JpaRepository<PromotionLink, UUID> {

    Optional<PromotionLink> findByLinkCode(String linkCode);

    Optional<PromotionLink> findByDistributorIdAndProductId(UUID distributorId, UUID productId);

    Page<PromotionLink> findByDistributorId(UUID distributorId, Pageable pageable);

    @Query("SELECT pl FROM PromotionLink pl WHERE " +
            "(:distributorId IS NULL OR pl.distributorId = :distributorId) " +
            "ORDER BY pl.clickCount DESC")
    Page<PromotionLink> findAdminList(@Param("distributorId") UUID distributorId, Pageable pageable);

    /** 商品推广聚合：销售额/佣金/点击/付款/推广人数 */
    @Query("SELECT COALESCE(SUM(pl.totalSales), 0), COALESCE(SUM(pl.totalCommission), 0), " +
            "COALESCE(SUM(pl.clickCount), 0), COALESCE(SUM(pl.paidCount), 0), COUNT(DISTINCT pl.distributorId) " +
            "FROM PromotionLink pl WHERE pl.productId = :productId")
    List<Object[]> aggregateByProduct(@Param("productId") UUID productId);

    /** 商品推广员排行（按推广销售额倒序） */
    Page<PromotionLink> findByProductIdOrderByTotalSalesDesc(UUID productId, Pageable pageable);
}
