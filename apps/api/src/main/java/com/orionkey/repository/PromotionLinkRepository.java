package com.orionkey.repository;

import com.orionkey.entity.PromotionLink;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
