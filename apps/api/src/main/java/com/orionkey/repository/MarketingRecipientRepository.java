package com.orionkey.repository;

import com.orionkey.entity.MarketingRecipient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

public interface MarketingRecipientRepository extends JpaRepository<MarketingRecipient, UUID> {

    Page<MarketingRecipient> findByCampaignIdOrderByCreatedAtDesc(UUID campaignId, Pageable pageable);

    long countByCampaignId(UUID campaignId);

    /** 账号合并：某用户收到的全部营销邮件记录 */
    java.util.List<MarketingRecipient> findByUserId(UUID userId);

    long countByCampaignIdAndDelivered(UUID campaignId, int delivered);

    /** 批量标记送达（doSendAsync 内逐条更新） */
    @Modifying
    @Query("UPDATE MarketingRecipient r SET r.delivered = :delivered, r.sentAt = :sentAt WHERE r.id = :id")
    int markDelivered(@Param("id") UUID id, @Param("delivered") int delivered, @Param("sentAt") LocalDateTime sentAt);

    /** 已打开人数（首开时间非空） */
    long countByCampaignIdAndOpenedAtIsNotNull(UUID campaignId);

    /** 已点击人数（首次点击时间非空） */
    long countByCampaignIdAndClickedAtIsNotNull(UUID campaignId);

    /** 已读用户分页（按首次打开时间倒序） */
    Page<MarketingRecipient> findByCampaignIdAndOpenedAtIsNotNullOrderByOpenedAtDesc(UUID campaignId, Pageable pageable);

    /** 记录邮件打开：首次写入时间，次数累加 */
    @Modifying
    @Query("UPDATE MarketingRecipient r SET r.openedCount = r.openedCount + 1, "
            + "r.openedAt = COALESCE(r.openedAt, :now) WHERE r.id = :id")
    int markOpened(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /** 记录邮件内链接点击：首次写入时间，次数累加 */
    @Modifying
    @Query("UPDATE MarketingRecipient r SET r.clickedCount = r.clickedCount + 1, "
            + "r.clickedAt = COALESCE(r.clickedAt, :now) WHERE r.id = :id")
    int markClicked(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /** 退信人数 */
    long countByCampaignIdAndBounced(UUID campaignId, int bounced);

    /** 退信回写：只记录首次退信，避免重复解析覆盖 */
    @Modifying
    @Query("UPDATE MarketingRecipient r SET r.bounced = 1, r.bouncedAt = :now, r.bounceReason = :reason "
            + "WHERE r.id = :id AND r.bounced = 0")
    int markBounced(@Param("id") UUID id, @Param("now") LocalDateTime now, @Param("reason") String reason);

    /** 退信邮件按收件邮箱匹配收件人快照（取最近一次发送记录） */
    java.util.List<MarketingRecipient> findByEmailIgnoreCaseOrderByCreatedAtDesc(String email);
}
