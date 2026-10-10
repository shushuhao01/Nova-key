package com.orionkey.repository;

import com.orionkey.entity.ChannelLink;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ChannelLinkRepository extends JpaRepository<ChannelLink, UUID> {

    Optional<ChannelLink> findByCode(String code);

    boolean existsByCode(String code);

    @Query("SELECT c FROM ChannelLink c WHERE " +
            "(:keyword IS NULL OR :keyword = '' OR LOWER(c.name) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(c.code) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(c.channel) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "ORDER BY c.createdAt DESC")
    Page<ChannelLink> findAdminList(@Param("keyword") String keyword, Pageable pageable);
}
