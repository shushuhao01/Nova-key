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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    Optional<Order> findByUsdtTxId(String usdtTxId);

    Page<Order> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<Order> findByEmailOrderByCreatedAtDesc(String email);

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

    // 管理后台订单列表 — 无搜索词
    // status=REFUNDED 时同时匹配全额退款（REFUNDED）与部分退款（PARTIALLY_REFUNDED）
    @Query("SELECT o FROM Order o WHERE " +
            "(:status IS NULL OR o.status = :status " +
            " OR (:status = com.orionkey.constant.OrderStatus.REFUNDED AND o.status = com.orionkey.constant.OrderStatus.PARTIALLY_REFUNDED)) " +
            "AND (:orderType IS NULL OR o.orderType = :orderType) " +
            "AND (:paymentMethod IS NULL OR :paymentMethod = '' OR o.paymentMethod = :paymentMethod) " +
            "AND (:isRiskFlagged IS NULL OR o.riskFlagged = :isRiskFlagged) " +
            "ORDER BY o.createdAt DESC")
    Page<Order> findAdminOrders(@Param("status") OrderStatus status,
                                @Param("orderType") OrderType orderType,
                                @Param("paymentMethod") String paymentMethod,
                                @Param("isRiskFlagged") Boolean isRiskFlagged,
                                Pageable pageable);

    // 管理后台订单列表 — 带搜索词（按订单ID、邮箱或商品名称搜索，keyword 保证非 null）
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN OrderItem oi ON oi.orderId = o.id WHERE " +
            "(:status IS NULL OR o.status = :status " +
            " OR (:status = com.orionkey.constant.OrderStatus.REFUNDED AND o.status = com.orionkey.constant.OrderStatus.PARTIALLY_REFUNDED)) " +
            "AND (:orderType IS NULL OR o.orderType = :orderType) " +
            "AND (:paymentMethod IS NULL OR :paymentMethod = '' OR o.paymentMethod = :paymentMethod) " +
            "AND (:isRiskFlagged IS NULL OR o.riskFlagged = :isRiskFlagged) " +
            "AND (str(o.id) LIKE :keywordPattern OR o.email LIKE :keywordPattern OR oi.productTitle LIKE :keywordPattern) " +
            "ORDER BY o.createdAt DESC")
    Page<Order> findAdminOrdersByKeyword(@Param("status") OrderStatus status,
                                         @Param("orderType") OrderType orderType,
                                         @Param("paymentMethod") String paymentMethod,
                                         @Param("isRiskFlagged") Boolean isRiskFlagged,
                                         @Param("keywordPattern") String keywordPattern,
                                         Pageable pageable);
}
