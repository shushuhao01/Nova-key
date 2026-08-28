package com.orionkey.repository;

import com.orionkey.entity.CustomerBinding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** 管理后台：某推广员绑定的客户分页（可按邮箱模糊搜索，新绑定在前） */
    @Query("SELECT cb FROM CustomerBinding cb WHERE cb.distributorId = :distId " +
            "AND (:keyword IS NULL OR :keyword = '' OR LOWER(cb.customerEmail) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "ORDER BY cb.createdAt DESC")
    Page<CustomerBinding> findAdminPage(@Param("distId") UUID distId,
                                        @Param("keyword") String keyword,
                                        Pageable pageable);
}
