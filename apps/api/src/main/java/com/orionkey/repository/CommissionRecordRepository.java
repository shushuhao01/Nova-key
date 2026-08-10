package com.orionkey.repository;

import com.orionkey.constant.CommissionStatus;
import com.orionkey.entity.CommissionRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface CommissionRecordRepository extends JpaRepository<CommissionRecord, UUID> {

    Page<CommissionRecord> findByDistributorIdOrderByCreatedAtDesc(UUID distributorId, Pageable pageable);

    List<CommissionRecord> findByOrderId(UUID orderId);

    @Query("SELECT cr FROM CommissionRecord cr WHERE " +
            "(:distributorId IS NULL OR cr.distributorId = :distributorId) " +
            "AND (:status IS NULL OR cr.status = :status) " +
            "AND (:from IS NULL OR cr.createdAt >= :from) " +
            "AND (:to IS NULL OR cr.createdAt < :to) " +
            "ORDER BY cr.createdAt DESC")
    Page<CommissionRecord> findAdminList(@Param("distributorId") UUID distributorId,
                                         @Param("status") CommissionStatus status,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to,
                                         Pageable pageable);

    @Query("SELECT COALESCE(SUM(cr.commissionAmount), 0) FROM CommissionRecord cr " +
            "WHERE cr.distributorId = :distributorId AND cr.status = :status")
    BigDecimal sumByDistributorAndStatus(@Param("distributorId") UUID distributorId,
                                         @Param("status") CommissionStatus status);

    /** 区间内佣金总额（不含已取消，按佣金创建时间） */
    @Query("SELECT COALESCE(SUM(cr.commissionAmount), 0) FROM CommissionRecord cr " +
            "WHERE cr.status != com.orionkey.constant.CommissionStatus.CANCELLED " +
            "AND cr.createdAt >= :from AND cr.createdAt < :to")
    BigDecimal sumCommissionAmountBetween(@Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /** 区间内待结算佣金（不含已取消，按佣金创建时间） */
    @Query("SELECT COALESCE(SUM(cr.commissionAmount), 0) FROM CommissionRecord cr " +
            "WHERE cr.status = com.orionkey.constant.CommissionStatus.PENDING " +
            "AND cr.createdAt >= :from AND cr.createdAt < :to")
    BigDecimal sumPendingBetween(@Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to);

    /**
     * 待结算佣金：订单已完成（COMPLETED）且完成时间超过结算延迟期。
     * 即"订单完成 + N 天"后佣金才可结算提现，防止退款套佣（退款会走 cancelCommissions 取消佣金）。
     */
    @Query("SELECT cr FROM CommissionRecord cr JOIN Order o ON o.id = cr.orderId " +
            "WHERE cr.status = 'PENDING' " +
            "AND o.status = com.orionkey.constant.OrderStatus.COMPLETED " +
            "AND o.completedAt IS NOT NULL AND o.completedAt < :before " +
            "ORDER BY cr.createdAt ASC")
    List<CommissionRecord> findPendingSettlement(@Param("before") java.time.LocalDateTime before);
}
