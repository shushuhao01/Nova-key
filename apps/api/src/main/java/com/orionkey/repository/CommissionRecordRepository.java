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
     * 返回 [销售额(只算直接推广者，与推广员排行各行销售额之和一致), 佣金合计(全量，含上级抽成，即平台实际支出),
     * 付款订单数(去重)]，剔除已取消佣金。按佣金创建时间落在 [from, to) 内统计，from/to 为服务层传入的非空哨兵值。
     */
    @Query(value = "SELECT " +
            "COALESCE(SUM(CASE WHEN NOT EXISTS (SELECT 1 FROM commission_records p " +
            "WHERE p.order_id = cr.order_id AND p.parent_distributor_id = cr.distributor_id) " +
            "THEN cr.order_amount ELSE 0 END), 0), " +
            "COALESCE(SUM(cr.commission_amount), 0), " +
            "COUNT(DISTINCT cr.order_id) " +
            "FROM commission_records cr WHERE cr.product_id = :productId AND cr.status::text != 'CANCELLED' " +
            "AND cr.created_at >= :from AND cr.created_at < :to",
            nativeQuery = true)
    List<Object[]> aggregateByProductAdmin(@Param("productId") UUID productId,
                                           @Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to);

    /**
     * 管理后台商品推广人数（去重，区间内）：直接推广成交的推广员 ∪ 有点击埋点的推广员，
     * 与 {@link #aggregatePromotersByProduct} 的分页总数保持一致（含只点击未成交的推广员）。
     */
    @Query(value = "SELECT COUNT(*) FROM (" +
            "SELECT cr.distributor_id AS did FROM commission_records cr " +
            "WHERE cr.product_id = :productId AND cr.status::text != 'CANCELLED' " +
            "AND cr.created_at >= :from AND cr.created_at < :to " + DIRECT_PROMOTER_ONLY +
            "UNION SELECT c.distributor_id AS did FROM distribution_clicks c " +
            "WHERE c.product_id = :productId AND c.created_at >= :from AND c.created_at < :to" +
            ") t",
            nativeQuery = true)
    long countPromotersByProduct(@Param("productId") UUID productId,
                                 @Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to);

    /**
     * 管理后台商品推广员排行（成交 ∪ 点击，按推广销售额倒序）：
     * 每行 [distributorId, 销售额, 佣金合计, 付款订单数(去重), 点击次数]，剔除已取消佣金，
     * 统计范围限定在 [from, to) 内（from/to 为服务层传入的非空哨兵值）。
     * 只点击未成交的推广员同样入榜（销售额/佣金/付款为 0），保证「点击排行」完整、
     * 且各推广员点击之和等于商品的点击总数。
     * 通过 {@link #DIRECT_PROMOTER_ONLY} 只统计“谁直接推广”，不把下级成交算进上级名下。
     */
    @Query(value = "SELECT t.did, COALESCE(SUM(t.sales), 0), COALESCE(SUM(t.commission), 0), " +
            "COALESCE(SUM(t.paid), 0), COALESCE(SUM(t.clicks), 0) FROM (" +
            "SELECT cr.distributor_id AS did, SUM(cr.order_amount) AS sales, " +
            "SUM(cr.commission_amount) AS commission, COUNT(DISTINCT cr.order_id) AS paid, CAST(0 AS numeric) AS clicks " +
            "FROM commission_records cr WHERE cr.product_id = :productId AND cr.status::text != 'CANCELLED' " +
            "AND cr.created_at >= :from AND cr.created_at < :to " +
            DIRECT_PROMOTER_ONLY + " GROUP BY cr.distributor_id " +
            "UNION ALL " +
            "SELECT c.distributor_id AS did, CAST(0 AS numeric) AS sales, CAST(0 AS numeric) AS commission, " +
            "CAST(0 AS bigint) AS paid, COUNT(*) AS clicks " +
            "FROM distribution_clicks c WHERE c.product_id = :productId " +
            "AND c.created_at >= :from AND c.created_at < :to GROUP BY c.distributor_id" +
            ") t GROUP BY t.did ORDER BY COALESCE(SUM(t.sales), 0) DESC, COALESCE(SUM(t.clicks), 0) DESC",
            countQuery = "SELECT COUNT(*) FROM (" +
                    "SELECT cr.distributor_id AS did FROM commission_records cr " +
                    "WHERE cr.product_id = :productId AND cr.status::text != 'CANCELLED' " +
                    "AND cr.created_at >= :from AND cr.created_at < :to " + DIRECT_PROMOTER_ONLY +
                    "UNION SELECT c.distributor_id AS did FROM distribution_clicks c " +
                    "WHERE c.product_id = :productId AND c.created_at >= :from AND c.created_at < :to" +
                    ") t",
            nativeQuery = true)
    Page<Object[]> aggregatePromotersByProduct(@Param("productId") UUID productId,
                                               @Param("from") LocalDateTime from,
                                               @Param("to") LocalDateTime to,
                                               Pageable pageable);

    /**
     * 只保留“直接推广者”的佣金记录：排除二级分销里上级的抽成记录。
     * 上级抽成记录与其下级的直接佣金记录同订单同商品项、order_amount 完全相同，
     * 且下级记录的 parent_distributor_id 指向该上级；故“存在某条把 cr.distributor_id 当作上级的记录”
     * 即判定 cr 为上级抽成记录，予以排除，避免下级的成交成绩被重复计入上级名下。
     */
    String DIRECT_PROMOTER_ONLY = "AND NOT EXISTS (SELECT 1 FROM commission_records p " +
            "WHERE p.order_id = cr.order_id AND p.parent_distributor_id = cr.distributor_id) ";

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
