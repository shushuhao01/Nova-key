package com.orionkey.repository;

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

    /**
     * 管理后台提现记录列表。原生 SQL + status::text 显式转文本比较，
     * 兼容 status 列为 varchar 或早期 PG 原生枚举（USER-DEFINED）两种情形，
     * 避免 System error 500（详见 DistributorRepository.findAdminList）。
     */
    @Query(value = "SELECT * FROM withdrawal_records WHERE " +
            "(:status IS NULL OR :status = '' OR status::text = CAST(:status AS text)) " +
            "AND (:from IS NULL OR created_at >= CAST(:from AS timestamp)) " +
            "AND (:to IS NULL OR created_at < CAST(:to AS timestamp)) " +
            "ORDER BY created_at DESC",
            countQuery = "SELECT COUNT(*) FROM withdrawal_records WHERE " +
            "(:status IS NULL OR :status = '' OR status::text = CAST(:status AS text)) " +
            "AND (:from IS NULL OR created_at >= CAST(:from AS timestamp)) " +
            "AND (:to IS NULL OR created_at < CAST(:to AS timestamp))",
            nativeQuery = true)
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
