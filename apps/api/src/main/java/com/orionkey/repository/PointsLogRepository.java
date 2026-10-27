package com.orionkey.repository;

import com.orionkey.entity.PointsLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PointsLogRepository extends JpaRepository<PointsLog, UUID> {

    Page<PointsLog> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** 账号合并：取某用户全部积分流水 */
    List<PointsLog> findByUserId(UUID userId);
}
