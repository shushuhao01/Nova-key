package com.orionkey.repository;

import com.orionkey.entity.SystemMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface SystemMessageRepository extends JpaRepository<SystemMessage, UUID> {

    Page<SystemMessage> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<SystemMessage> findByReadFalseOrderByCreatedAtDesc(Pageable pageable);

    long countByReadFalse();

    @Modifying
    @Query("update SystemMessage m set m.read = true where m.read = false")
    int markAllRead();
}
