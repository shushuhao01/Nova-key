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

    /** 按核销码查找任意领取记录（自动生成的唯一码通过它反查活动） */
    Optional<UserCoupon> findFirstByCodeOrderByCreatedAtDesc(String code);

    long countByCampaignId(UUID campaignId);

    long countByCampaignIdAndStatus(UUID campaignId, String status);

    List<UserCoupon> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** 个人中心优惠券分页：按状态过滤（CLAIMED/USED/ALL） */
    org.springframework.data.domain.Page<UserCoupon> findByUserIdOrderByCreatedAtDesc(UUID userId, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<UserCoupon> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, String status, org.springframework.data.domain.Pageable pageable);

    boolean existsByCodeAndUserId(String code, UUID userId);

    boolean existsByCodeAndEmail(String code, String email);

    boolean existsByCodeAndUserIdAndStatus(String code, UUID userId, String status);

    boolean existsByCodeAndEmailAndStatus(String code, String email, String status);

    /** 原子核销：仅当状态仍为 CLAIMED 时置为 USED，返回受影响行数（并发下防重复使用） */
    @Modifying
    @Query("UPDATE UserCoupon u SET u.status = 'USED', u.usedAt = :usedAt, u.orderId = :orderId WHERE u.id = :id AND u.status = 'CLAIMED'")
    int markUsed(@Param("id") UUID id, @Param("usedAt") LocalDateTime usedAt, @Param("orderId") UUID orderId);
}

