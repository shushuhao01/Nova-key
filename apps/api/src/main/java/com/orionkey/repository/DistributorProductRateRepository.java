package com.orionkey.repository;

import com.orionkey.entity.DistributorProductRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DistributorProductRateRepository extends JpaRepository<DistributorProductRate, UUID> {
    Optional<DistributorProductRate> findByDistributorIdAndProductId(UUID distributorId, UUID productId);
}
