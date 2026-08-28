package com.orionkey.repository;

import com.orionkey.constant.CardKeyStatus;
import com.orionkey.entity.CardKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CardKeyRepository extends JpaRepository<CardKey, UUID> {

    @Query(value = "SELECT * FROM card_keys WHERE product_id = :productId " +
            "AND ((:specId IS NULL AND spec_id IS NULL) OR spec_id = CAST(:specId AS uuid)) " +
            "AND status = 'AVAILABLE' ORDER BY created_at ASC LIMIT :count " +
            "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<CardKey> findAndLockAvailable(@Param("productId") UUID productId,
                                       @Param("specId") UUID specId,
                                       @Param("count") int count);

    long countByProductIdAndStatus(UUID productId, CardKeyStatus status);

    long countByProductIdAndSpecIdAndStatus(UUID productId, UUID specId, CardKeyStatus status);

    long countByProductIdAndSpecIdIsNullAndStatus(UUID productId, CardKeyStatus status);

    List<CardKey> findByOrderId(UUID orderId);

    /** 订单下已锁定（下单预留）的卡密，用于过期释放回库存 */
    List<CardKey> findByOrderIdAndStatus(UUID orderId, CardKeyStatus status);

    /** 订单明细下已锁定（下单预留）的卡密，用于发货时转 SOLD */
    List<CardKey> findByOrderItemIdAndStatus(UUID orderItemId, CardKeyStatus status);

    boolean existsByContentAndProductId(String content, UUID productId);

    @Query("SELECT CASE WHEN COUNT(ck) > 0 THEN true ELSE false END FROM CardKey ck " +
            "WHERE ck.content = :content AND ck.productId = :productId " +
            "AND ((:specId IS NULL AND ck.specId IS NULL) OR ck.specId = :specId)")
    boolean existsByContentAndProductIdAndSpecId(@Param("content") String content,
                                                 @Param("productId") UUID productId,
                                                 @Param("specId") UUID specId);

    @Query("SELECT COUNT(ck) FROM CardKey ck WHERE ck.productId = :productId " +
            "AND ((:specId IS NULL AND ck.specId IS NULL) OR ck.specId = :specId) " +
            "AND ck.status <> :excludeStatus")
    long countByProductIdAndSpecIdExcludingStatus(@Param("productId") UUID productId,
                                                   @Param("specId") UUID specId,
                                                   @Param("excludeStatus") CardKeyStatus excludeStatus);

    /** 管理后台卡密列表：支持状态集合（空=全部）与内容关键词，默认按创建时间倒序 */
    @Query("SELECT ck FROM CardKey ck WHERE ck.productId = :productId " +
            "AND ((:specId IS NULL AND ck.specId IS NULL) OR ck.specId = :specId) " +
            "AND (:statuses IS EMPTY OR ck.status IN :statuses) " +
            "AND (:keyword IS NULL OR :keyword = '' OR LOWER(ck.content) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "ORDER BY ck.createdAt DESC")
    Page<CardKey> findAdminList(@Param("productId") UUID productId,
                                @Param("specId") UUID specId,
                                @Param("statuses") Collection<CardKeyStatus> statuses,
                                @Param("keyword") String keyword,
                                Pageable pageable);

    /** 管理后台卡密列表（已售出，按售出时间倒序） */
    @Query("SELECT ck FROM CardKey ck WHERE ck.productId = :productId " +
            "AND ((:specId IS NULL AND ck.specId IS NULL) OR ck.specId = :specId) " +
            "AND ck.status = com.orionkey.constant.CardKeyStatus.SOLD " +
            "AND (:keyword IS NULL OR :keyword = '' OR LOWER(ck.content) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "ORDER BY ck.soldAt DESC")
    Page<CardKey> findAdminSoldList(@Param("productId") UUID productId,
                                    @Param("specId") UUID specId,
                                    @Param("keyword") String keyword,
                                    Pageable pageable);

    /** 全局已售出记录（按售出时间倒序），支持商品名/卡密/用户邮箱/推广员综合搜索 */
    @Query("SELECT ck FROM CardKey ck LEFT JOIN Order o ON o.id = ck.orderId " +
            "WHERE ck.status = com.orionkey.constant.CardKeyStatus.SOLD " +
            "AND (:keyword IS NULL OR :keyword = '' " +
            "  OR LOWER(ck.content) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "  OR (o.email IS NOT NULL AND LOWER(o.email) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "  OR ck.productId IN (SELECT p.id FROM Product p WHERE LOWER(p.title) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "  OR o.referralDistributorId IN (SELECT d.id FROM Distributor d " +
            "       WHERE LOWER(d.distributorCode) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "       OR d.userId IN (SELECT u.id FROM User u WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "                        OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%'))))) " +
            "ORDER BY ck.soldAt DESC")
    Page<CardKey> findSoldRecords(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT ck.status, COUNT(ck) FROM CardKey ck " +
            "WHERE ck.productId = :productId AND ((:specId IS NULL AND ck.specId IS NULL) OR ck.specId = :specId) " +
            "GROUP BY ck.status")
    List<Object[]> countByProductIdAndSpecIdGroupByStatus(@Param("productId") UUID productId,
                                                          @Param("specId") UUID specId);

    @Modifying
    @Query("UPDATE CardKey ck SET ck.status = :newStatus " +
            "WHERE ck.productId = :productId " +
            "AND ((:specId IS NULL AND ck.specId IS NULL) OR ck.specId = :specId) " +
            "AND ck.status = :oldStatus")
    int updateStatusByProductIdAndSpecId(@Param("productId") UUID productId,
                                         @Param("specId") UUID specId,
                                         @Param("oldStatus") CardKeyStatus oldStatus,
                                         @Param("newStatus") CardKeyStatus newStatus);
}
