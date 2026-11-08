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
