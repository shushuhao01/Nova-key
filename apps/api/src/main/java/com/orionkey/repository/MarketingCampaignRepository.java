package com.orionkey.repository;

import com.orionkey.entity.MarketingCampaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MarketingCampaignRepository extends JpaRepository<MarketingCampaign, UUID> {

    Page<MarketingCampaign> findByStatus(String status, Pageable pageable);

    Page<MarketingCampaign> findByTitleContaining(String keyword, Pageable pageable);

    long countByStatus(String status);
}
