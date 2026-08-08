package com.orionkey.repository;

import com.orionkey.entity.MarketingCampaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarketingCampaignRepository extends JpaRepository<MarketingCampaign, UUID> {

    Page<MarketingCampaign> findByStatus(String status, Pageable pageable);

    Page<MarketingCampaign> findByTitleContaining(String keyword, Pageable pageable);

    long countByStatus(String status);

    /** 营销邮件记录（recordType=EMAIL 或旧数据 null），支持标题/状态过滤 */
    @Query("SELECT c FROM MarketingCampaign c WHERE (c.recordType IS NULL OR c.recordType <> 'COUPON') "
            + "AND (:keyword IS NULL OR LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%'))) "
            + "AND (:status IS NULL OR :status = '' OR c.status = :status)")
    Page<MarketingCampaign> findEmailCampaigns(@Param("keyword") String keyword, @Param("status") String status, Pageable pageable);

    /** 纯优惠券记录（recordType=COUPON） */
    @Query("SELECT c FROM MarketingCampaign c WHERE c.recordType = 'COUPON' "
            + "AND (:keyword IS NULL OR LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<MarketingCampaign> findCoupons(@Param("keyword") String keyword, Pageable pageable);

    /** 到点待发的定时邮件 */
    List<MarketingCampaign> findByStatusAndSendAtLessThanEqual(String status, LocalDateTime time);

    /** 悲观写锁：领取优惠券时锁住活动行，防止并发超发（原生 SQL FOR UPDATE 兼容 PG 与 H2） */
    @Query(value = "SELECT * FROM marketing_campaigns WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<MarketingCampaign> findByIdForUpdate(@Param("id") UUID id);
}
