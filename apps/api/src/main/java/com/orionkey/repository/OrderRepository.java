package com.orionkey.repository;

import com.orionkey.constant.OrderStatus;
import com.orionkey.constant.OrderType;
import com.orionkey.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    Optional<Order> findByUsdtTxId(String usdtTxId);

    /** 按微信 JSAPI 独立商户单号定位订单（JSAPI 单号无法还原为订单 UUID，需按列反查） */
    Optional<Order> findByJsapiTradeNo(String jsapiTradeNo);

    Page<Order> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<Order> findByEmailOrderByCreatedAtDesc(String email);

    /** 未注册用户订购时填写的邮箱（匿名客户，营销邮件受众建议用） */
    @Query("SELECT DISTINCT o.email FROM Order o WHERE o.userId IS NULL AND o.email IS NOT NULL AND o.email <> '' ORDER BY o.email")
    List<String> findDistinctAnonymousEmails();

    long countByUserId(UUID userId);

    // ── 分销推广：分销订单（推广链接成交）统计与列表 ──

    /** 分销订单（promotionLinkId 非空、已支付/已发货/已完成，按支付时间区间） */
    @Query("SELECT o FROM Order o WHERE o.promotionLinkId IS NOT NULL " +
            "AND (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED) " +
            "AND o.paidAt IS NOT NULL AND o.paidAt >= :from AND o.paidAt < :to " +
            "ORDER BY o.paidAt DESC")
    Page<Order> findDistributionOrders(@Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to,
                                       Pageable pageable);

    /** 分销订单销售额合计（按支付时间区间） */
    @Query("SELECT COALESCE(SUM(o.actualAmount), 0) FROM Order o WHERE o.promotionLinkId IS NOT NULL " +
            "AND (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED) " +
            "AND o.paidAt IS NOT NULL AND o.paidAt >= :from AND o.paidAt < :to")
    BigDecimal sumDistributionSales(@Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);

    /** 分销成交订单数（按支付时间区间，from/to 由服务层传入非空哨兵值=不限） */
    @Query("SELECT COUNT(o) FROM Order o WHERE o.promotionLinkId IS NOT NULL " +
            "AND (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED) " +
            "AND o.paidAt IS NOT NULL AND o.paidAt >= :from AND o.paidAt < :to")
    long countDistributionOrdersRange(@Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to);

    /**
     * 分销员推广累计成交额（全部时段）：按 referral_distributor_id 关联（全店与商品推广链接都算），
     * 已付款/已发货/已完成订单按支付时间汇总。
     */
    @Query("SELECT COALESCE(SUM(o.actualAmount), 0) FROM Order o WHERE o.referralDistributorId = :distId " +
            "AND (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED) " +
            "AND o.paidAt IS NOT NULL")
    BigDecimal sumSalesByDistributorAll(@Param("distId") UUID distId);

    /** 分销员推广成交额（自 from 起的支付时间，from 恒非空） */
    @Query("SELECT COALESCE(SUM(o.actualAmount), 0) FROM Order o WHERE o.referralDistributorId = :distId " +
            "AND (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED) " +
            "AND o.paidAt IS NOT NULL AND o.paidAt >= :from")
    BigDecimal sumSalesByDistributorSince(@Param("distId") UUID distId, @Param("from") LocalDateTime from);

    /** 分销员推广成交订单数（全部时段）：按 referral_distributor_id 关联（全店与商品推广链接都算），已付款/已发货/已完成 */
    @Query("SELECT COUNT(o) FROM Order o WHERE o.referralDistributorId = :distId " +
            "AND (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED) " +
            "AND o.paidAt IS NOT NULL")
    long countPaidOrdersByDistributorAll(@Param("distId") UUID distId);

    /** 某推广员名下、客户邮箱匹配的订单（按创建时间倒序），用于团队-客户聚合购买明细 */
    @Query("SELECT o FROM Order o WHERE o.referralDistributorId = :distId AND LOWER(o.email) = :email " +
            "ORDER BY o.createdAt DESC")
    List<Order> findDistributorOrdersByEmail(@Param("distId") UUID distId,
                                             @Param("email") String email);

    // ── 客户管理（注册用户 / 匿名邮箱统计） ──

    /** 注册用户成交订单数（已支付/已发货/已完成） */
    @Query("SELECT COUNT(o) FROM Order o WHERE o.userId = :userId AND (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED)")
    long countPaidByUserId(@Param("userId") UUID userId);

    /** 注册用户累计消费金额（已支付/已发货/已完成） */
    @Query("SELECT COALESCE(SUM(o.actualAmount), 0) FROM Order o WHERE o.userId = :userId AND (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED)")
    BigDecimal sumPaidByUserId(@Param("userId") UUID userId);

    /** 成交注册客户数 */
    @Query("SELECT COUNT(DISTINCT o.userId) FROM Order o WHERE o.userId IS NOT NULL AND (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED)")
    long countPaidRegisteredCustomers();

    /** 成交匿名客户数（按邮箱去重） */
    @Query("SELECT COUNT(DISTINCT o.email) FROM Order o WHERE o.userId IS NULL AND o.email IS NOT NULL AND o.email <> '' AND (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED)")
    long countPaidAnonymousCustomers();

    /** 匿名客户总数（orders.user_id IS NULL 的邮箱去重） */
    @Query(value = "SELECT COUNT(DISTINCT email) FROM orders WHERE user_id IS NULL AND email IS NOT NULL AND email <> ''", nativeQuery = true)
    long countAnonymousEmails();

    /** 本月新增匿名客户（首单发生在本月及以后） */
    @Query(value = "SELECT COUNT(*) FROM (SELECT email FROM orders WHERE user_id IS NULL AND email IS NOT NULL AND email <> '' GROUP BY email HAVING MIN(created_at) >= :since) t", nativeQuery = true)
    long countNewAnonymousEmails(@Param("since") LocalDateTime since);

    /** 匿名客户邮箱分页列表（按最近一次下单倒序） */
    @Query(value = "SELECT email FROM orders WHERE user_id IS NULL AND email IS NOT NULL AND email <> '' GROUP BY email ORDER BY MAX(created_at) DESC",
            countQuery = "SELECT COUNT(DISTINCT email) FROM orders WHERE user_id IS NULL AND email IS NOT NULL AND email <> ''",
            nativeQuery = true)
    Page<String> findAnonymousEmails(Pageable pageable);

    /** 匿名客户邮箱分页列表（关键词过滤） */
    @Query(value = "SELECT email FROM orders WHERE user_id IS NULL AND email IS NOT NULL AND email <> '' AND email LIKE :kw GROUP BY email ORDER BY MAX(created_at) DESC",
            countQuery = "SELECT COUNT(DISTINCT email) FROM orders WHERE user_id IS NULL AND email IS NOT NULL AND email <> '' AND email LIKE :kw",
            nativeQuery = true)
    Page<String> findAnonymousEmailsByKeyword(@Param("kw") String kw, Pageable pageable);

    Page<Order> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, OrderStatus status, Pageable pageable);

    List<Order> findByEmailInOrderByCreatedAtDesc(List<String> emails);

    /** 匿名客户订单（仅 user_id IS NULL，即未注册的购买记录，按邮箱批量取） */
    List<Order> findByEmailInAndUserIdIsNullOrderByCreatedAtDesc(List<String> emails);

    /** 匿名客户订单（仅 user_id IS NULL，详情页用） */
    List<Order> findByEmailAndUserIdIsNullOrderByCreatedAtDesc(String email);

    List<Order> findByIdIn(List<UUID> ids);

    /** 自动发货兜底：查询所有已支付（PAID）但尚未发货（DELIVERED）的订单 */
    List<Order> findByStatus(OrderStatus status);

    @Query("SELECT o FROM Order o WHERE o.status = com.orionkey.constant.OrderStatus.PENDING AND o.expiresAt < :now")
    List<Order> findExpiredOrders(@Param("now") LocalDateTime now);

    long countByUserIdAndStatus(UUID userId, OrderStatus status);

    long countByClientIpAndStatus(String clientIp, OrderStatus status);

    long countByEmailAndStatus(String email, OrderStatus status);

    /** 悲观写锁：SELECT ... FOR UPDATE，用于防止并发发货等竞态条件 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);

    /** 自动完成：已发货（DELIVERED）且发货时间超过 24 小时的订单 */
    @Query("SELECT o FROM Order o WHERE o.status = com.orionkey.constant.OrderStatus.DELIVERED AND o.deliveredAt IS NOT NULL AND o.deliveredAt < :cutoff")
    List<Order> findAutoCompleteOrders(@Param("cutoff") LocalDateTime cutoff);

    /** 退款处理中（已发起退款但微信尚未确认到账）的订单 */
    @Query("SELECT o FROM Order o WHERE o.refundStatus = 'PENDING'")
    List<Order> findPendingRefunds();

    /** 按商户退款单号定位订单（退款结果通知回调用） */
    Optional<Order> findByOutRefundNo(String outRefundNo);

    @Query("SELECT o FROM Order o WHERE o.riskFlagged = true ORDER BY o.createdAt DESC")
    Page<Order> findRiskFlaggedOrders(Pageable pageable);

    // Dashboard aggregate queries
    @Query("SELECT COALESCE(SUM(o.actualAmount), 0) FROM Order o WHERE (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED) AND o.paidAt >= :since")
    BigDecimal sumSalesSince(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(o) FROM Order o WHERE (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED) AND o.paidAt >= :since")
    long countPaidOrdersSince(@Param("since") LocalDateTime since);

    // 报表通知：区间统计（paidAt ∈ [from, to)）
    @Query("SELECT COALESCE(SUM(o.actualAmount), 0) FROM Order o WHERE (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED) AND o.paidAt >= :from AND o.paidAt < :to")
    BigDecimal sumSalesBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(o) FROM Order o WHERE (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED) AND o.paidAt >= :from AND o.paidAt < :to")
    long countPaidOrdersBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(o) FROM Order o WHERE (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED)")
    long countTotalPaidOrders();

    long count();

    // ══════════════════════ 渠道归因 ══════════════════════

    /** 渠道区间下单数（按创建时间） */
    @Query("SELECT COUNT(o) FROM Order o WHERE o.channelCode = :channel AND o.createdAt >= :from AND o.createdAt < :to")
    long countChannelOrdersBetween(@Param("channel") String channel,
                                   @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 渠道区间已支付订单数（按支付时间） */
    @Query("SELECT COUNT(o) FROM Order o WHERE o.channelCode = :channel " +
            "AND (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED) " +
            "AND o.paidAt >= :from AND o.paidAt < :to")
    long countChannelPaidBetween(@Param("channel") String channel,
                                 @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 渠道区间销售额（按支付时间） */
    @Query("SELECT COALESCE(SUM(o.actualAmount), 0) FROM Order o WHERE o.channelCode = :channel " +
            "AND (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED) " +
            "AND o.paidAt >= :from AND o.paidAt < :to")
    BigDecimal sumChannelSalesBetween(@Param("channel") String channel,
                                      @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 批量统计各渠道累计已支付订单数（全量，按渠道分组）[channelCode, count] */
    @Query("SELECT o.channelCode, COUNT(o) FROM Order o WHERE o.channelCode IN :channels " +
            "AND (o.status = com.orionkey.constant.OrderStatus.PAID OR o.status = com.orionkey.constant.OrderStatus.DELIVERED OR o.status = com.orionkey.constant.OrderStatus.COMPLETED) " +
            "GROUP BY o.channelCode")
    List<Object[]> countPaidGroupByChannel(@Param("channels") Collection<String> channels);

    // 管理后台订单列表 — 无搜索词
    // statuses 支持多选（逗号分隔解析为 List，空筛选传 null）；包含 REFUNDED 时同时匹配全额退款（REFUNDED）与部分退款（PARTIALLY_REFUNDED）
    @Query("SELECT o FROM Order o WHERE " +
            "(:statuses IS NULL OR o.status IN :statuses " +
            " OR (:containsRefunded = TRUE AND o.status = com.orionkey.constant.OrderStatus.PARTIALLY_REFUNDED)) " +
            "AND (:orderType IS NULL OR o.orderType = :orderType) " +
            "AND (:paymentMethod IS NULL OR :paymentMethod = '' OR o.paymentMethod = :paymentMethod) " +
            "AND (:isRiskFlagged IS NULL OR o.riskFlagged = :isRiskFlagged) " +
            "AND o.createdAt >= :from AND o.createdAt < :to " +
            "ORDER BY o.createdAt DESC")
    Page<Order> findAdminOrders(@Param("statuses") List<OrderStatus> statuses,
                                @Param("containsRefunded") boolean containsRefunded,
                                @Param("orderType") OrderType orderType,
                                @Param("paymentMethod") String paymentMethod,
                                @Param("isRiskFlagged") Boolean isRiskFlagged,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to,
                                Pageable pageable);

    // 管理后台订单列表 — 带搜索词（按订单ID、邮箱、商品名称或推广员搜索，keyword 保证非 null）
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN OrderItem oi ON oi.orderId = o.id WHERE " +
            "(:statuses IS NULL OR o.status IN :statuses " +
            " OR (:containsRefunded = TRUE AND o.status = com.orionkey.constant.OrderStatus.PARTIALLY_REFUNDED)) " +
            "AND (:orderType IS NULL OR o.orderType = :orderType) " +
            "AND (:paymentMethod IS NULL OR :paymentMethod = '' OR o.paymentMethod = :paymentMethod) " +
            "AND (:isRiskFlagged IS NULL OR o.riskFlagged = :isRiskFlagged) " +
            "AND (str(o.id) LIKE :keywordPattern OR o.email LIKE :keywordPattern OR oi.productTitle LIKE :keywordPattern " +
            " OR o.referralDistributorId IN (SELECT d.id FROM Distributor d WHERE d.distributorCode LIKE :keywordPattern " +
            "   OR d.userId IN (SELECT u.id FROM User u WHERE u.username LIKE :keywordPattern OR u.email LIKE :keywordPattern))) " +
            "AND o.createdAt >= :from AND o.createdAt < :to " +
            "ORDER BY o.createdAt DESC")
    Page<Order> findAdminOrdersByKeyword(@Param("statuses") List<OrderStatus> statuses,
                                         @Param("containsRefunded") boolean containsRefunded,
                                         @Param("orderType") OrderType orderType,
                                         @Param("paymentMethod") String paymentMethod,
                                         @Param("isRiskFlagged") Boolean isRiskFlagged,
                                         @Param("keywordPattern") String keywordPattern,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to,
                                         Pageable pageable);

    // 管理后台订单统计（与列表完全相同的筛选条件；日期按创建时间 [from, to)）
    // 返回单行：[总订单数, 总金额(全部订单), 有效金额(已支付且未退款), 有效订单数, 无效订单数, 总客户数]
    @Query("SELECT COUNT(o), " +
            "SUM(o.actualAmount), " +
            "SUM(CASE WHEN o.status IN (com.orionkey.constant.OrderStatus.PAID, com.orionkey.constant.OrderStatus.DELIVERED, com.orionkey.constant.OrderStatus.COMPLETED) THEN o.actualAmount END), " +
            "COUNT(CASE WHEN o.status IN (com.orionkey.constant.OrderStatus.PAID, com.orionkey.constant.OrderStatus.DELIVERED, com.orionkey.constant.OrderStatus.COMPLETED) THEN 1 END), " +
            "COUNT(CASE WHEN o.status IN (com.orionkey.constant.OrderStatus.PENDING, com.orionkey.constant.OrderStatus.EXPIRED, com.orionkey.constant.OrderStatus.REFUNDED, com.orionkey.constant.OrderStatus.PARTIALLY_REFUNDED) THEN 1 END), " +
            "COUNT(DISTINCT o.email) " +
            "FROM Order o WHERE o.id IN (" +
            "SELECT o2.id FROM Order o2 LEFT JOIN OrderItem oi ON oi.orderId = o2.id WHERE " +
            "(:statuses IS NULL OR o2.status IN :statuses " +
            " OR (:containsRefunded = TRUE AND o2.status = com.orionkey.constant.OrderStatus.PARTIALLY_REFUNDED)) " +
            "AND (:orderType IS NULL OR o2.orderType = :orderType) " +
            "AND (:paymentMethod IS NULL OR :paymentMethod = '' OR o2.paymentMethod = :paymentMethod) " +
            "AND (:isRiskFlagged IS NULL OR o2.riskFlagged = :isRiskFlagged) " +
            "AND (:hasKeyword = FALSE OR str(o2.id) LIKE :keywordPattern OR o2.email LIKE :keywordPattern " +
            " OR oi.productTitle LIKE :keywordPattern " +
            " OR o2.referralDistributorId IN (SELECT d.id FROM Distributor d WHERE d.distributorCode LIKE :keywordPattern " +
            "   OR d.userId IN (SELECT u.id FROM User u WHERE u.username LIKE :keywordPattern OR u.email LIKE :keywordPattern))) " +
            ") AND o.createdAt >= :from AND o.createdAt < :to")
    List<Object[]> statAdminOrders(@Param("statuses") List<OrderStatus> statuses,
                                   @Param("containsRefunded") boolean containsRefunded,
                                   @Param("orderType") OrderType orderType,
                                   @Param("paymentMethod") String paymentMethod,
                                   @Param("isRiskFlagged") Boolean isRiskFlagged,
                                   @Param("hasKeyword") boolean hasKeyword,
                                   @Param("keywordPattern") String keywordPattern,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    // ============================================================
    // 业绩结算（原生 SQL，PostgreSQL）
    // 有效订单口径：PAID / DELIVERED / COMPLETED（未退款）
    // ============================================================

    /**
     * 业绩结算汇总（按订单创建时间 [from, to)）。
     * 返回单行：[总业绩(含退款), 退款金额, 有效金额, 有效订单数, 退款单数, 总订单数, 客户数]
     */
    @Query(value = "SELECT " +
            "COALESCE(SUM(CASE WHEN o.status IN ('PAID','DELIVERED','COMPLETED','REFUNDED','PARTIALLY_REFUNDED') " +
            "THEN COALESCE(o.actual_amount, 0) ELSE 0 END), 0), " +
            "COALESCE(SUM(COALESCE(o.refunded_amount, 0)), 0), " +
            "COALESCE(SUM(CASE WHEN o.status IN ('PAID','DELIVERED','COMPLETED') " +
            "THEN COALESCE(o.actual_amount, 0) ELSE 0 END), 0), " +
            "COUNT(CASE WHEN o.status IN ('PAID','DELIVERED','COMPLETED') THEN 1 END), " +
            "COUNT(CASE WHEN COALESCE(o.refunded_amount, 0) > 0 THEN 1 END), " +
            "COUNT(*), " +
            "COUNT(DISTINCT o.email) " +
            "FROM orders o WHERE o.created_at >= :from AND o.created_at < :to",
            nativeQuery = true)
    List<Object[]> statSettlement(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 业绩趋势（按 fmt 分桶：YYYY-MM-DD 按日 / YYYY-MM 按月）。
     * 返回：[bucket, 总业绩, 有效金额, 有效订单数, 退款金额]
     */
    @Query(value = "SELECT to_char(o.created_at, :fmt) AS bucket, " +
            "COALESCE(SUM(CASE WHEN o.status IN ('PAID','DELIVERED','COMPLETED','REFUNDED','PARTIALLY_REFUNDED') " +
            "THEN COALESCE(o.actual_amount, 0) ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN o.status IN ('PAID','DELIVERED','COMPLETED') " +
            "THEN COALESCE(o.actual_amount, 0) ELSE 0 END), 0), " +
            "COUNT(CASE WHEN o.status IN ('PAID','DELIVERED','COMPLETED') THEN 1 END), " +
            "COALESCE(SUM(COALESCE(o.refunded_amount, 0)), 0) " +
            "FROM orders o WHERE o.created_at >= :from AND o.created_at < :to " +
            "GROUP BY bucket ORDER BY bucket",
            nativeQuery = true)
    List<Object[]> settlementTrend(@Param("fmt") String fmt,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    /** 支付方式分布（仅有效订单）：[payment_method, 金额, 订单数] */
    @Query(value = "SELECT COALESCE(NULLIF(o.payment_method, ''), 'unknown') AS pm, " +
            "COALESCE(SUM(COALESCE(o.actual_amount, 0)), 0), COUNT(*) " +
            "FROM orders o WHERE o.created_at >= :from AND o.created_at < :to " +
            "AND o.status IN ('PAID','DELIVERED','COMPLETED') " +
            "GROUP BY pm ORDER BY 2 DESC",
            nativeQuery = true)
    List<Object[]> settlementPaymentMethods(@Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to);

    /** 订单类型分布（仅有效订单）：[order_type, 金额, 订单数] */
    @Query(value = "SELECT CAST(o.order_type AS text) AS ot, " +
            "COALESCE(SUM(COALESCE(o.actual_amount, 0)), 0), COUNT(*) " +
            "FROM orders o WHERE o.created_at >= :from AND o.created_at < :to " +
            "AND o.status IN ('PAID','DELIVERED','COMPLETED') " +
            "GROUP BY ot ORDER BY 2 DESC",
            nativeQuery = true)
    List<Object[]> settlementOrderTypes(@Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to);

    /** 分销佣金合计（一级 + 二级，按有效订单统计） */
    @Query(value = "SELECT COALESCE(SUM(COALESCE(cr.commission_amount, 0) + COALESCE(cr.parent_commission_amount, 0)), 0) " +
            "FROM commission_records cr JOIN orders o ON o.id = cr.order_id " +
            "WHERE o.created_at >= :from AND o.created_at < :to " +
            "AND o.status IN ('PAID','DELIVERED','COMPLETED')",
            nativeQuery = true)
    BigDecimal sumSettlementCommission(@Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);

    /** 分销佣金 Top10：[分销编号, 名称, 佣金合计, 订单数] */
    @Query(value = "SELECT COALESCE(d.distributor_code, ''), " +
            "COALESCE(u.username, u.email, d.distributor_code), " +
            "COALESCE(SUM(COALESCE(cr.commission_amount, 0) + COALESCE(cr.parent_commission_amount, 0)), 0), " +
            "COUNT(DISTINCT cr.order_id) " +
            "FROM commission_records cr " +
            "JOIN orders o ON o.id = cr.order_id " +
            "JOIN distributors d ON d.id = cr.distributor_id " +
            "LEFT JOIN users u ON u.id = d.user_id " +
            "WHERE o.created_at >= :from AND o.created_at < :to " +
            "AND o.status IN ('PAID','DELIVERED','COMPLETED') " +
            "GROUP BY d.distributor_code, u.username, u.email ORDER BY 3 DESC LIMIT 10",
            nativeQuery = true)
    List<Object[]> findSettlementTopDistributors(@Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to);
}
