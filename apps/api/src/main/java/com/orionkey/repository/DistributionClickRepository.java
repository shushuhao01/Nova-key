package com.orionkey.repository;

import com.orionkey.entity.DistributionClick;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DistributionClickRepository extends JpaRepository<DistributionClick, UUID> {
    long countByPromotionLinkIdAndIp(UUID promotionLinkId, String ip);
    long countByDistributorId(UUID distributorId);
}
