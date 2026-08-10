package com.orionkey.repository;

import com.orionkey.entity.UserMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserMessageRepository extends JpaRepository<UserMessage, UUID> {

    long countByUserIdAndReadFalse(UUID userId);

    List<UserMessage> findTop5ByUserIdOrderByCreatedAtDesc(UUID userId);

    @Query("SELECT m FROM UserMessage m WHERE m.userId = :userId " +
            "AND (:category IS NULL OR m.category = :category) " +
            "AND (:unreadOnly = false OR m.read = false) " +
            "ORDER BY m.createdAt DESC")
    Page<UserMessage> findByUserId(@Param("userId") UUID userId,
                                   @Param("category") String category,
                                   @Param("unreadOnly") boolean unreadOnly,
                                   Pageable pageable);

    @Modifying
    @Query("UPDATE UserMessage m SET m.read = true, m.readAt = CURRENT_TIMESTAMP WHERE m.userId = :userId AND m.read = false")
    int markAllRead(@Param("userId") UUID userId);
}
