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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DistributorRepository extends JpaRepository<Distributor, UUID> {

    Optional<Distributor> findByUserId(UUID userId);

    Optional<Distributor> findByDistributorCode(String code);

    Optional<Distributor> findByInviteCode(String inviteCode);

    List<Distributor> findByParentId(UUID parentId);

    @Query("SELECT d FROM Distributor d WHERE " +
            "(:status IS NULL OR d.status = :status) " +
            "AND (:keyword IS NULL OR :keyword = '' OR LOWER(d.distributorCode) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Distributor> findAdminList(@Param("status") DistributorStatus status,
                                    @Param("keyword") String keyword,
                                    Pageable pageable);

    long countByStatus(DistributorStatus status);

    @Query("SELECT MAX(d.customRate) FROM Distributor d WHERE d.customRate IS NOT NULL")
    java.math.BigDecimal findMaxCustomRate();

    /** 悲观行锁查询：提现等涉及余额变动的操作必须加锁防止并发超扣 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Distributor d WHERE d.id = :id")
    Optional<Distributor> findByIdWithLock(@Param("id") UUID id);
}
