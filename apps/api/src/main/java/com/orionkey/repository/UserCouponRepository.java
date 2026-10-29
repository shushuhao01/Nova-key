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

    /** 按核销码 + 状态查找（营销邮件预分配为 SENT，领取后转 CLAIMED） */
    Optional<UserCoupon> findFirstByCodeAndStatusOrderByCreatedAtDesc(String code, String status);

    /** 按一批核销码批量查（已读用户列表展示领取状态用） */
    List<UserCoupon> findByCodeIn(java.util.Collection<String> codes);

    long countByCampaignId(UUID campaignId);

    long countByCampaignIdAndStatus(UUID campaignId, String status);

    /** 已领取数量（排除仅推送未领取的 SENT 记录） */
    long countByCampaignIdAndStatusNot(UUID campaignId, String status);

    /** 某用户在某活动下已领取数量（每人限领校验） */
    long countByCampaignIdAndUserId(UUID campaignId, UUID userId);

    /** 某邮箱在某活动下已领取数量（每人限领校验，未登录邮箱领取场景） */
    long countByCampaignIdAndEmail(UUID campaignId, String email);

    List<UserCoupon> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** 个人中心优惠券分页：按状态过滤（CLAIMED/USED/ALL） */
    org.springframework.data.domain.Page<UserCoupon> findByUserIdOrderByCreatedAtDesc(UUID userId, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<UserCoupon> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, String status, org.springframework.data.domain.Pageable pageable);

    /** 个人中心优惠券（全部）：排除仅推送未领取的 SENT 记录 */
    org.springframework.data.domain.Page<UserCoupon> findByUserIdAndStatusNotOrderByCreatedAtDesc(UUID userId, String status, org.springframework.data.domain.Pageable pageable);

    boolean existsByCodeAndUserId(String code, UUID userId);

    boolean existsByCodeAndEmail(String code, String email);

    boolean existsByCodeAndUserIdAndStatus(String code, UUID userId, String status);

    boolean existsByCodeAndEmailAndStatus(String code, String email, String status);

    /** 原子核销：仅当状态仍为 CLAIMED 时置为 USED，返回受影响行数（并发下防重复使用） */
    @Modifying
    @Query("UPDATE UserCoupon u SET u.status = 'USED', u.usedAt = :usedAt, u.orderId = :orderId WHERE u.id = :id AND u.status = 'CLAIMED'")
    int markUsed(@Param("id") UUID id, @Param("usedAt") LocalDateTime usedAt, @Param("orderId") UUID orderId);

    /**
     * 历史数据校正：旧版本发送营销邮件时直接把预分配的券置为 CLAIMED（claimedAt = 发送时刻），
     * 导致「已领取」等于发送人数。此处把"命中收件人快照、领取时间≈创建时间、且未被使用"的记录
     * 回退为 SENT（已推送未领取）；用户再次点击邮件里的领取链接即可重新领取。
     */
    @Modifying
    @Query(value = "UPDATE user_coupons uc SET status = 'SENT', claimed_at = NULL "
            + "WHERE uc.status = 'CLAIMED' AND uc.claimed_at IS NOT NULL "
            + "AND uc.claimed_at <= uc.created_at + interval '10 seconds' "
            + "AND EXISTS (SELECT 1 FROM marketing_recipients r "
            + "JOIN marketing_campaigns e ON e.id = r.campaign_id "
            + "WHERE e.coupon_ref_id = uc.campaign_id "
            + "AND r.code = uc.code "
            + "AND ((uc.user_id IS NOT NULL AND r.user_id = uc.user_id) "
            + "OR (uc.email IS NOT NULL AND r.email = uc.email)))",
            nativeQuery = true)
    int resetLegacyPreIssuedCoupons();
}

