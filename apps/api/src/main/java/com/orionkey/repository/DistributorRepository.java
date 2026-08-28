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

    /** 管理后台：某推广员的下级成员分页（可按编码/用户名/邮箱模糊搜索，新加入在前） */
    @Query("SELECT d FROM Distributor d WHERE d.parentId = :parentId " +
            "AND (:keyword IS NULL OR :keyword = '' OR LOWER(d.distributorCode) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR d.userId IN (SELECT u.id FROM User u WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')))) " +
            "ORDER BY d.createdAt DESC")
    Page<Distributor> findSubordinatesPage(@Param("parentId") UUID parentId,
                                           @Param("keyword") String keyword,
                                           Pageable pageable);

    /**
     * 管理后台分销员列表。from/to 由服务层传入非空哨兵值（null 时间参数出现在
     * "IS NULL" 谓词时 PG 无法推断类型，报 could not determine data type of parameter）。
     * keyword 匹配分销员编码 / 用户名 / 邮箱（通过子查询关联用户表）。
     */
    @Query("SELECT d FROM Distributor d WHERE " +
            "(:status IS NULL OR d.status = :status) " +
            "AND (:keyword IS NULL OR :keyword = '' OR LOWER(d.distributorCode) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR d.userId IN (SELECT u.id FROM User u WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')))) " +
            "AND d.createdAt >= :from AND d.createdAt < :to " +
            "ORDER BY d.createdAt DESC")
    Page<Distributor> findAdminList(@Param("status") DistributorStatus status,
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
