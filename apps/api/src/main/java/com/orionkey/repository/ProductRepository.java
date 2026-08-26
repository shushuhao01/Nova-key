package com.orionkey.repository;

import com.orionkey.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    // 首页商品列表 — 无搜索词（仅展示首页可见商品；私域商品通过链接直达详情）
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 AND p.enabled = true AND p.homepageVisible = true " +
            "AND (:categoryId IS NULL OR p.categoryId = :categoryId)")
    Page<Product> findPublicProducts(@Param("categoryId") UUID categoryId,
                                     Pageable pageable);

    // 首页商品列表 — 带搜索词（keyword 保证非 null）
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 AND p.enabled = true AND p.homepageVisible = true " +
            "AND (:categoryId IS NULL OR p.categoryId = :categoryId) " +
            "AND LOWER(p.title) LIKE :keywordPattern")
    Page<Product> findPublicProductsByKeyword(@Param("categoryId") UUID categoryId,
                                              @Param("keywordPattern") String keywordPattern,
                                              Pageable pageable);

    // 分销/统计侧商品列表 — 不过滤首页可见性（可推广性由商品佣金配置决定，与首页显示相互独立）
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 AND p.enabled = true")
    Page<Product> findEnabledProducts(Pageable pageable);

    // 商品短链解析：通过短码查询未删除商品
    Optional<Product> findByShortCodeAndIsDeleted(String shortCode, int isDeleted);

    // 短码唯一性校验（生成商品短链编码时使用）
    boolean existsByShortCode(String shortCode);

    // 管理后台商品列表 — 无搜索词
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 " +
            "AND (:categoryId IS NULL OR p.categoryId = :categoryId) " +
            "AND (:isEnabled IS NULL OR p.enabled = :isEnabled)")
    Page<Product> findAdminProducts(@Param("categoryId") UUID categoryId,
                                    @Param("isEnabled") Boolean isEnabled,
                                    Pageable pageable);

    // 管理后台商品列表 — 带搜索词（keyword 保证非 null）
    @Query("SELECT p FROM Product p WHERE p.isDeleted = 0 " +
            "AND (:categoryId IS NULL OR p.categoryId = :categoryId) " +
            "AND LOWER(p.title) LIKE :keywordPattern " +
            "AND (:isEnabled IS NULL OR p.enabled = :isEnabled)")
    Page<Product> findAdminProductsByKeyword(@Param("categoryId") UUID categoryId,
                                             @Param("keywordPattern") String keywordPattern,
                                             @Param("isEnabled") Boolean isEnabled,
                                             Pageable pageable);

    long countByCategoryIdAndIsDeleted(UUID categoryId, int isDeleted);

    // 一次性迁移：将已有规格的商品自动设置 spec_enabled=true
    @Modifying
    @Query("UPDATE Product p SET p.specEnabled = true WHERE p.specEnabled = false " +
            "AND p.isDeleted = 0 AND EXISTS (" +
            "SELECT 1 FROM ProductSpec s WHERE s.productId = p.id AND s.isDeleted = 0)")
    int migrateSpecEnabled();
}
