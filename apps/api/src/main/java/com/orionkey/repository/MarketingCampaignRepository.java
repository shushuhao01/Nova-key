package com.orionkey.repository;

import com.orionkey.entity.MarketingCampaign;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface MarketingCampaignRepository extends JpaRepository<MarketingCampaign, UUID> {

    Page<MarketingCampaign> findByStatus(String status, Pageable pageable);

    Page<MarketingCampaign> findByTitleContaining(String keyword, Pageable pageable);

    long countByStatus(String status);

    /** 悲观写锁：领取优惠券时锁住活动行，防止并发超发 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM MarketingCampaign c WHERE c.id = :id")
    Optional<MarketingCampaign> findByIdForUpdate(@Param("id") UUID id);
}
