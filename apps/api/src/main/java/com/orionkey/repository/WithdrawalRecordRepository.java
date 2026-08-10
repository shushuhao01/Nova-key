package com.orionkey.repository;

import com.orionkey.constant.WithdrawalStatus;
import com.orionkey.entity.WithdrawalRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WithdrawalRecordRepository extends JpaRepository<WithdrawalRecord, UUID> {

    Page<WithdrawalRecord> findByDistributorIdOrderByCreatedAtDesc(UUID distributorId, Pageable pageable);

    @Query("SELECT wr FROM WithdrawalRecord wr WHERE " +
            "(:status = '' OR wr.status = :status) " +
            "AND (:from IS NULL OR wr.createdAt >= :from) " +
            "AND (:to IS NULL OR wr.createdAt < :to) " +
            "ORDER BY wr.createdAt DESC")
    Page<WithdrawalRecord> findAdminList(@Param("status") String status,
                                         @Param("from") java.time.LocalDateTime from,
                                         @Param("to") java.time.LocalDateTime to,
                                         Pageable pageable);

    /** 区间内提现申请金额合计（按申请创建时间） */
    @Query("SELECT COALESCE(SUM(wr.amount), 0) FROM WithdrawalRecord wr " +
            "WHERE wr.createdAt >= :from AND wr.createdAt < :to")
    BigDecimal sumAmountBetween(@Param("from") java.time.LocalDateTime from,
                                @Param("to") java.time.LocalDateTime to);

    Optional<WithdrawalRecord> findByOutBillNo(String outBillNo);

    @Query("SELECT wr FROM WithdrawalRecord wr WHERE wr.status = 'PROCESSING' " +
            "AND wr.transferredAt < :before ORDER BY wr.transferredAt ASC")
    List<WithdrawalRecord> findProcessingTimeout(@Param("before") java.time.LocalDateTime before);
}
