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

    /**
     * 管理后台佣金记录列表。from/to 由服务层传入非空哨兵值（null 时间参数出现在
     * "IS NULL" 谓词时 PG 无法推断类型，报 could not determine data type of parameter）。
     */
    @Query("SELECT cr FROM CommissionRecord cr WHERE " +
            "(:distributorId IS NULL OR cr.distributorId = :distributorId) " +
            "AND (:status IS NULL OR cr.status = :status) " +
            "AND cr.createdAt >= :from AND cr.createdAt < :to " +
            "ORDER BY cr.createdAt DESC")
    Page<CommissionRecord> findAdminList(@Param("distributorId") UUID distributorId,
                                         @Param("status") CommissionStatus status,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to,
                                         Pageable pageable);

    /** 分销员指定状态佣金合计（status::text 兼容枚举/varchar 列） */
    @Query(value = "SELECT COALESCE(SUM(commission_amount), 0) FROM commission_records " +
            "WHERE distributor_id = :distributorId AND status::text = :status",
            nativeQuery = true)
    BigDecimal sumByDistributorAndStatus(@Param("distributorId") UUID distributorId,
                                         @Param("status") String status);

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

    /** 区间内已结算佣金（按佣金创建时间） */
    @Query("SELECT COALESCE(SUM(cr.commissionAmount), 0) FROM CommissionRecord cr " +
            "WHERE cr.status = com.orionkey.constant.CommissionStatus.SETTLED " +
            "AND cr.createdAt >= :from AND cr.createdAt < :to")
    BigDecimal sumSettledBetween(@Param("from") LocalDateTime from,
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

    /** 我作为上级抽成的订单项 key 集合（orderId, orderItemId），用于前台区分"自己推广/下级抽成" */
    @Query("SELECT cr.orderId, cr.orderItemId FROM CommissionRecord cr WHERE cr.parentDistributorId = :parentId")
    List<Object[]> findParentCommissionItemKeys(@Param("parentId") UUID parentId);

    /** 指定下级为我创造的抽成金额合计 */
    @Query("SELECT COALESCE(SUM(cr.parentCommissionAmount), 0) FROM CommissionRecord cr " +
            "WHERE cr.parentDistributorId = :parentId AND cr.distributorId = :subId")
    BigDecimal sumParentCommissionBySub(@Param("parentId") UUID parentId,
                                        @Param("subId") UUID subId);

    /** 订单项对应的销售下级记录（用于展示抽成来源的推广员） */
    List<CommissionRecord> findByOrderIdAndOrderItemIdAndParentDistributorId(
            UUID orderId, UUID orderItemId, UUID parentDistributorId);
}
