package com.orionkey.repository;

import com.orionkey.entity.UserCoupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserCouponRepository extends JpaRepository<UserCoupon, UUID> {

    Optional<UserCoupon> findFirstByCodeAndUserIdAndStatusOrderByCreatedAtDesc(String code, UUID userId, String status);

    Optional<UserCoupon> findFirstByCodeAndEmailAndStatusOrderByCreatedAtDesc(String code, String email, String status);

    long countByCampaignId(UUID campaignId);

    long countByCampaignIdAndStatus(UUID campaignId, String status);

    List<UserCoupon> findByUserIdOrderByCreatedAtDesc(UUID userId);

    boolean existsByCodeAndUserId(String code, UUID userId);

    boolean existsByCodeAndEmail(String code, String email);

    /** 原子核销：仅当状态仍为 CLAIMED 时置为 USED，返回受影响行数（并发下防重复使用） */
    @Modifying
    @Query("UPDATE UserCoupon u SET u.status = 'USED', u.usedAt = :usedAt, u.orderId = :orderId WHERE u.id = :id AND u.status = 'CLAIMED'")
    int markUsed(@Param("id") UUID id, @Param("usedAt") LocalDateTime usedAt, @Param("orderId") UUID orderId);
}

