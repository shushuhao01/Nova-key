package com.orionkey.repository;

import com.orionkey.constant.CommissionStatus;
import com.orionkey.entity.CommissionRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface CommissionRecordRepository extends JpaRepository<CommissionRecord, UUID> {

    Page<CommissionRecord> findByDistributorIdOrderByCreatedAtDesc(UUID distributorId, Pageable pageable);

    List<CommissionRecord> findByOrderId(UUID orderId);

    @Query("SELECT cr FROM CommissionRecord cr WHERE " +
            "(:distributorId IS NULL OR cr.distributorId = :distributorId) " +
            "AND (:status IS NULL OR cr.status = :status) " +
            "ORDER BY cr.createdAt DESC")
    Page<CommissionRecord> findAdminList(@Param("distributorId") UUID distributorId,
                                         @Param("status") CommissionStatus status,
                                         Pageable pageable);

    @Query("SELECT COALESCE(SUM(cr.commissionAmount), 0) FROM CommissionRecord cr " +
            "WHERE cr.distributorId = :distributorId AND cr.status = :status")
    BigDecimal sumByDistributorAndStatus(@Param("distributorId") UUID distributorId,
                                         @Param("status") CommissionStatus status);

    @Query("SELECT cr FROM CommissionRecord cr WHERE cr.status = 'PENDING' " +
            "AND cr.createdAt < :before ORDER BY cr.createdAt ASC")
    List<CommissionRecord> findPendingSettlement(@Param("before") java.time.LocalDateTime before);
}
