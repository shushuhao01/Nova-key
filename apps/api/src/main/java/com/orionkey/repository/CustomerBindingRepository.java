package com.orionkey.repository;

import com.orionkey.entity.CustomerBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CustomerBindingRepository extends JpaRepository<CustomerBinding, UUID> {

    /** 查找客户邮箱的活跃绑定（保护期内） */
    @Query("SELECT cb FROM CustomerBinding cb WHERE cb.customerEmail = :email " +
            "AND cb.protectionExpiresAt > CURRENT_TIMESTAMP ORDER BY cb.createdAt DESC")
    Optional<CustomerBinding> findActiveBindingByEmail(@Param("email") String email);

    Optional<CustomerBinding> findByCustomerEmailAndDistributorId(String email, UUID distributorId);

    long countByDistributorId(UUID distributorId);
}
