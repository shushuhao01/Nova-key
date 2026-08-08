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

    long countByCampaignIdAndDelivered(UUID campaignId, int delivered);

    /** 批量标记送达（doSendAsync 内逐条更新） */
    @Modifying
    @Query("UPDATE MarketingRecipient r SET r.delivered = :delivered, r.sentAt = :sentAt WHERE r.id = :id")
    int markDelivered(@Param("id") UUID id, @Param("delivered") int delivered, @Param("sentAt") LocalDateTime sentAt);
}
