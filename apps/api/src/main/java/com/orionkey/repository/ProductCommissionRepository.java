package com.orionkey.repository;

import com.orionkey.entity.ProductCommission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProductCommissionRepository extends JpaRepository<ProductCommission, UUID> {
    Optional<ProductCommission> findByProductId(UUID productId);
}
