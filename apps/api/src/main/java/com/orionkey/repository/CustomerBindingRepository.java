package com.orionkey.repository;

import com.orionkey.entity.CustomerBinding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerBindingRepository extends JpaRepository<CustomerBinding, UUID> {

    /** 查找客户邮箱的活跃绑定（保护期内） */
    @Query("SELECT cb FROM CustomerBinding cb WHERE cb.customerEmail = :email " +
            "AND cb.protectionExpiresAt > CURRENT_TIMESTAMP ORDER BY cb.createdAt DESC")
    Optional<CustomerBinding> findActiveBindingByEmail(@Param("email") String email);

    Optional<CustomerBinding> findByCustomerEmailAndDistributorId(String email, UUID distributorId);

    long countByDistributorId(UUID distributorId);

    /** 某推广员全部客户绑定（团队-客户合并读取用） */
    List<CustomerBinding> findByDistributorIdOrderByCreatedAtDesc(UUID distributorId);

    /**
     * 推广员团队客户数 = 客户绑定 ∪ 订单归属（后台手动绑定推广员的历史订单也会计入），
     * 按客户标识去重：注册用户按 user_id，匿名客户按邮箱/手机号。
     */
    @Query(value = "SELECT COUNT(*) FROM (" +
            "SELECT CASE WHEN customer_user_id IS NOT NULL THEN 'u:' || customer_user_id::text " +
            "            ELSE 'e:' || LOWER(COALESCE(customer_email, '')) END AS k " +
            "FROM customer_bindings WHERE distributor_id = :distId " +
            "UNION " +
            "SELECT CASE WHEN o.user_id IS NOT NULL THEN 'u:' || o.user_id::text " +
            "            WHEN NULLIF(LOWER(o.email), '') IS NOT NULL THEN 'e:' || LOWER(o.email) " +
            "            ELSE 'p:' || o.phone END AS k " +
            "FROM orders o WHERE o.referral_distributor_id = :distId " +
            "AND (o.user_id IS NOT NULL OR NULLIF(LOWER(o.email), '') IS NOT NULL OR NULLIF(o.phone, '') IS NOT NULL)" +
            ") t", nativeQuery = true)
    long countTeamCustomers(@Param("distId") UUID distId);

    /** 批量按客户邮箱查绑定（已读用户列表展示推广员用） */
    List<CustomerBinding> findByCustomerEmailIn(java.util.Collection<String> customerEmails);

    /** 批量按注册客户查绑定（已读用户列表展示推广员用） */
    List<CustomerBinding> findByCustomerUserIdIn(java.util.Collection<UUID> customerUserIds);

    /** 账号合并：某注册客户的全部绑定关系 */
    List<CustomerBinding> findByCustomerUserId(UUID customerUserId);

    /** 管理后台：某推广员绑定的客户分页（可按邮箱 / 注册账号用户名·邮箱·手机号模糊搜索，新绑定在前） */
    @Query("SELECT cb FROM CustomerBinding cb WHERE cb.distributorId = :distId " +
            "AND (:keyword IS NULL OR :keyword = '' " +
            "  OR LOWER(cb.customerEmail) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "  OR cb.customerUserId IN (SELECT u.id FROM User u WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "       OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "       OR (u.phone IS NOT NULL AND u.phone LIKE CONCAT('%', :keyword, '%'))) " +
            "  OR cb.promotionLinkId IN (SELECT pl.id FROM PromotionLink pl " +
            "       WHERE LOWER(pl.linkCode) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            ") " +
            "ORDER BY cb.createdAt DESC")
    Page<CustomerBinding> findAdminPage(@Param("distId") UUID distId,
                                        @Param("keyword") String keyword,
                                        Pageable pageable);
}
