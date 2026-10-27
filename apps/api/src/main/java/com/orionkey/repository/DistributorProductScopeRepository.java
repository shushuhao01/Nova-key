package com.orionkey.repository;

import com.orionkey.entity.DistributorProductScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DistributorProductScopeRepository extends JpaRepository<DistributorProductScope, UUID> {
    List<DistributorProductScope> findByDistributorId(UUID distributorId);
    boolean existsByDistributorIdAndProductId(UUID distributorId, UUID productId);
    void deleteByDistributorId(UUID distributorId);
}
