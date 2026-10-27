package com.orionkey.repository;

import com.orionkey.entity.DistributorCommissionTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DistributorCommissionTierRepository extends JpaRepository<DistributorCommissionTier, UUID> {
    List<DistributorCommissionTier> findByDistributorIdOrderByTierOrderAsc(UUID distributorId);
    List<DistributorCommissionTier> findByDistributorIdAndEnabledTrueOrderByTierOrderAsc(UUID distributorId);
    void deleteByDistributorId(UUID distributorId);
}
