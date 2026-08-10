package com.orionkey.repository;

import com.orionkey.constant.WithdrawalStatus;
import com.orionkey.entity.WithdrawalRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WithdrawalRecordRepository extends JpaRepository<WithdrawalRecord, UUID> {

    Page<WithdrawalRecord> findByDistributorIdOrderByCreatedAtDesc(UUID distributorId, Pageable pageable);

    @Query("SELECT wr FROM WithdrawalRecord wr WHERE " +
            "(:status IS NULL OR wr.status = :status) " +
            "ORDER BY wr.createdAt DESC")
    Page<WithdrawalRecord> findAdminList(@Param("status") WithdrawalStatus status, Pageable pageable);

    Optional<WithdrawalRecord> findByOutBillNo(String outBillNo);

    @Query("SELECT wr FROM WithdrawalRecord wr WHERE wr.status = 'PROCESSING' " +
            "AND wr.transferredAt < :before ORDER BY wr.transferredAt ASC")
    List<WithdrawalRecord> findProcessingTimeout(@Param("before") java.time.LocalDateTime before);
}
