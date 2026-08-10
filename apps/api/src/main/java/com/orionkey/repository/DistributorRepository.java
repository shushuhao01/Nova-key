package com.orionkey.repository;

import com.orionkey.entity.Distributor;
import com.orionkey.constant.DistributorStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DistributorRepository extends JpaRepository<Distributor, UUID> {

    Optional<Distributor> findByUserId(UUID userId);

    List<Distributor> findByUserIdIn(Collection<UUID> userIds);

    /** 按绑定的微信 openid 查找分销员（关注事件关联） */
    Optional<Distributor> findByWechatOpenid(String wechatOpenid);

    Optional<Distributor> findByDistributorCode(String code);

    Optional<Distributor> findByInviteCode(String inviteCode);

    List<Distributor> findByParentId(UUID parentId);

    /**
     * 管理后台分销员列表。使用原生 SQL 且 status::text 显式转文本比较，
     * 兼容 status 列为 varchar 或早期 PG 原生枚举（USER-DEFINED）两种情形，
     * 避免 Hibernate 绑定 varchar 参数与原生枚举列比较时报
     * "operator does not exist ... = character varying" → System error 500。
     */
    @Query(value = "SELECT * FROM distributors WHERE " +
            "(:status IS NULL OR status::text = :status) " +
            "AND (:keyword IS NULL OR :keyword = '' OR LOWER(distributor_code) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "AND (:from IS NULL OR created_at >= :from) " +
            "AND (:to IS NULL OR created_at < :to) " +
            "ORDER BY created_at DESC",
            countQuery = "SELECT COUNT(*) FROM distributors WHERE " +
            "(:status IS NULL OR status::text = :status) " +
            "AND (:keyword IS NULL OR :keyword = '' OR LOWER(distributor_code) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "AND (:from IS NULL OR created_at >= :from) " +
            "AND (:to IS NULL OR created_at < :to)",
            nativeQuery = true)
    Page<Distributor> findAdminList(@Param("status") String status,
                                    @Param("keyword") String keyword,
                                    @Param("from") java.time.LocalDateTime from,
                                    @Param("to") java.time.LocalDateTime to,
                                    Pageable pageable);

    long countByStatus(DistributorStatus status);

    @Query("SELECT MAX(d.customRate) FROM Distributor d WHERE d.customRate IS NOT NULL")
    java.math.BigDecimal findMaxCustomRate();

    /** 悲观行锁查询：提现等涉及余额变动的操作必须加锁防止并发超扣 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Distributor d WHERE d.id = :id")
    Optional<Distributor> findByIdWithLock(@Param("id") UUID id);
}
