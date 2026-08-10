package com.orionkey.repository;

import com.orionkey.entity.CommissionTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CommissionTierRepository extends JpaRepository<CommissionTier, UUID> {
    List<CommissionTier> findByEnabledTrueOrderByTierOrderAsc();
    List<CommissionTier> findAllByOrderByTierOrderAsc();
}
