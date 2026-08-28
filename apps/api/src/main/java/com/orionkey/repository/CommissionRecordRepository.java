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
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CommissionRecordRepository extends JpaRepository<CommissionRecord, UUID> {

    Page<CommissionRecord> findByDistributorIdOrderByCreatedAtDesc(UUID distributorId, Pageable pageable);

    List<CommissionRecord> findByOrderId(UUID orderId);

    /** 某推广员在某订单下的佣金记录（团队-客户聚合购买佣金） */
    List<CommissionRecord> findByDistributorIdAndOrderId(UUID distributorId, UUID orderId);

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

    /** 分销员指定状态佣金合计（全部时段，按佣金创建时间） */
    @Query("SELECT COALESCE(SUM(cr.commissionAmount), 0) FROM CommissionRecord cr " +
            "WHERE cr.distributorId = :distId AND cr.status = :status")
    BigDecimal sumByDistributorAndStatusAll(@Param("distId") UUID distId,
                                            @Param("status") CommissionStatus status);

    /** 分销员指定状态佣金合计（自 from 起，from 恒非空） */
    @Query("SELECT COALESCE(SUM(cr.commissionAmount), 0) FROM CommissionRecord cr " +
            "WHERE cr.distributorId = :distId AND cr.status = :status AND cr.createdAt >= :from")
    BigDecimal sumByDistributorAndStatusSince(@Param("distId") UUID distId,
                                              @Param("status") CommissionStatus status,
                                              @Param("from") LocalDateTime from);

    /** 分销员指定状态佣金合计（区间 [from, to) 内按佣金创建时间统计） */
    @Query("SELECT COALESCE(SUM(cr.commissionAmount), 0) FROM CommissionRecord cr " +
            "WHERE cr.distributorId = :distId AND cr.status = :status " +
            "AND cr.createdAt >= :from AND cr.createdAt < :to")
    BigDecimal sumByDistributorAndStatusBetween(@Param("distId") UUID distId,
                                                @Param("status") CommissionStatus status,
                                                @Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to);

    /** 分销员累计佣金（全部时段，不含已取消） */
    @Query("SELECT COALESCE(SUM(cr.commissionAmount), 0) FROM CommissionRecord cr " +
            "WHERE cr.distributorId = :distId AND cr.status != com.orionkey.constant.CommissionStatus.CANCELLED")
    BigDecimal sumTotalByDistributorAll(@Param("distId") UUID distId);

    /** 分销员累计佣金（自 from 起，from 恒非空，不含已取消） */
    @Query("SELECT COALESCE(SUM(cr.commissionAmount), 0) FROM CommissionRecord cr " +
            "WHERE cr.distributorId = :distId AND cr.status != com.orionkey.constant.CommissionStatus.CANCELLED " +
            "AND cr.createdAt >= :from")
    BigDecimal sumTotalByDistributorSince(@Param("distId") UUID distId, @Param("from") LocalDateTime from);

    /**
     * 分销员商品维度佣金聚合（含全店推广链接带来的成交）：按商品分组返回
     * [productId, 佣金合计, 成交订单数(去重)]，剔除已取消佣金。
     */
    @Query(value = "SELECT cr.product_id, COALESCE(SUM(cr.commission_amount), 0), COUNT(DISTINCT cr.order_id) " +
            "FROM commission_records cr WHERE cr.distributor_id = :distId AND cr.product_id IS NOT NULL " +
            "AND cr.status::text != 'CANCELLED' GROUP BY cr.product_id",
            nativeQuery = true)
    List<Object[]> aggregateCommissionByProduct(@Param("distId") UUID distId);

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
     * 管理后台商品维度推广聚合（佣金记录口径，含全店推广与商品推广链接成交）：
     * 返回 [销售额(order_amount 分摊合计), 佣金合计, 付款订单数(去重), 推广人数(去重)]，剔除已取消佣金。
     */
    @Query(value = "SELECT COALESCE(SUM(cr.order_amount), 0), COALESCE(SUM(cr.commission_amount), 0), " +
            "COUNT(DISTINCT cr.order_id), COUNT(DISTINCT cr.distributor_id) " +
            "FROM commission_records cr WHERE cr.product_id = :productId AND cr.status::text != 'CANCELLED'",
            nativeQuery = true)
    List<Object[]> aggregateByProductAdmin(@Param("productId") UUID productId);

    /**
     * 管理后台商品推广员排行（佣金记录口径，含全店推广与商品推广链接成交）：
     * 按推广销售额倒序分页，每行 [distributorId, 销售额, 佣金合计, 付款订单数(去重)]，剔除已取消佣金。
     */
    @Query(value = "SELECT cr.distributor_id, COALESCE(SUM(cr.order_amount), 0), COALESCE(SUM(cr.commission_amount), 0), " +
            "COUNT(DISTINCT cr.order_id) FROM commission_records cr " +
            "WHERE cr.product_id = :productId AND cr.status::text != 'CANCELLED' " +
            "GROUP BY cr.distributor_id ORDER BY COALESCE(SUM(cr.order_amount), 0) DESC",
            countQuery = "SELECT COUNT(DISTINCT cr.distributor_id) FROM commission_records cr " +
                    "WHERE cr.product_id = :productId AND cr.status::text != 'CANCELLED'",
            nativeQuery = true)
    Page<Object[]> aggregatePromotersByProduct(@Param("productId") UUID productId, Pageable pageable);

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

    /** 按 ID 批量查询（申请提现时校验归属与状态） */
    List<CommissionRecord> findByIdIn(Collection<UUID> ids);

    /** 分销员可提现的佣金记录（已结算 + 结算拒绝可重新勾选），按创建时间倒序 */
    List<CommissionRecord> findByDistributorIdAndStatusInOrderByCreatedAtDesc(
            UUID distributorId, Collection<CommissionStatus> statuses);

    /** 某提现单关联的佣金记录（审批/拒绝/结算时联动更新状态） */
    List<CommissionRecord> findByWithdrawalId(UUID withdrawalId);

    /**
     * 可结算的待结算佣金合计：订单已完成（COMPLETED）且完成时间超过结算延迟期，但尚未被定时任务结算。
     * 用于"可结算"状态展示与可提现余额口径（可结算部分可直接申请提现）。
     */
    @Query("SELECT COALESCE(SUM(cr.commissionAmount), 0) FROM CommissionRecord cr JOIN Order o ON o.id = cr.orderId " +
            "WHERE cr.distributorId = :distId AND cr.status = 'PENDING' " +
            "AND o.status = com.orionkey.constant.OrderStatus.COMPLETED " +
            "AND o.completedAt IS NOT NULL AND o.completedAt < :before")
    BigDecimal sumSettlablePendingByDistributor(@Param("distId") UUID distId,
                                                @Param("before") java.time.LocalDateTime before);

    /** 同 sumSettlablePendingByDistributor，但限定佣金创建时间 >= from（用于"本月"等区间口径，from 恒非空） */
    @Query("SELECT COALESCE(SUM(cr.commissionAmount), 0) FROM CommissionRecord cr JOIN Order o ON o.id = cr.orderId " +
            "WHERE cr.distributorId = :distId AND cr.status = 'PENDING' AND cr.createdAt >= :from " +
            "AND o.status = com.orionkey.constant.OrderStatus.COMPLETED " +
            "AND o.completedAt IS NOT NULL AND o.completedAt < :before")
    BigDecimal sumSettlablePendingByDistributorSince(@Param("distId") UUID distId,
                                                     @Param("before") java.time.LocalDateTime before,
                                                     @Param("from") java.time.LocalDateTime from);

    /** 同 sumSettlablePendingByDistributor，但限定佣金创建时间在区间 [from, to) 内 */
    @Query("SELECT COALESCE(SUM(cr.commissionAmount), 0) FROM CommissionRecord cr JOIN Order o ON o.id = cr.orderId " +
            "WHERE cr.distributorId = :distId AND cr.status = 'PENDING' " +
            "AND cr.createdAt >= :from AND cr.createdAt < :to " +
            "AND o.status = com.orionkey.constant.OrderStatus.COMPLETED " +
            "AND o.completedAt IS NOT NULL AND o.completedAt < :before")
    BigDecimal sumSettlablePendingByDistributorBetween(@Param("distId") UUID distId,
                                                       @Param("before") java.time.LocalDateTime before,
                                                       @Param("from") java.time.LocalDateTime from,
                                                       @Param("to") java.time.LocalDateTime to);
}
