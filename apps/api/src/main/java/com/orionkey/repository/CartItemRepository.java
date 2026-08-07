package com.orionkey.repository;

import com.orionkey.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    List<CartItem> findByUserId(UUID userId);

    List<CartItem> findBySessionToken(String sessionToken);

    /**
     * 查找用户购物车中的指定商品+规格。specId 可能为 null，
     * 派生查询会生成 `= NULL`（永假），必须手写 IS NULL 处理。
     */
    @Query("SELECT c FROM CartItem c WHERE c.userId = :userId AND c.productId = :productId " +
           "AND ((:specId IS NULL AND c.specId IS NULL) OR c.specId = :specId)")
    Optional<CartItem> findByUserIdAndProductIdAndSpecId(
            @Param("userId") UUID userId,
            @Param("productId") UUID productId,
            @Param("specId") UUID specId);

    @Query("SELECT c FROM CartItem c WHERE c.sessionToken = :sessionToken AND c.productId = :productId " +
           "AND ((:specId IS NULL AND c.specId IS NULL) OR c.specId = :specId)")
    Optional<CartItem> findBySessionTokenAndProductIdAndSpecId(
            @Param("sessionToken") String sessionToken,
            @Param("productId") UUID productId,
            @Param("specId") UUID specId);

    @Transactional
    void deleteBySessionToken(String sessionToken);
}
