package com.orionkey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * 推广员级推广商品范围明细（配合 distributors.product_scope_mode 使用）：
 * BLACKLIST = 该表中的商品不允许推广；WHITELIST = 仅该表中的商品允许推广。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "distributor_product_scopes",
        uniqueConstraints = {@UniqueConstraint(columnNames = {"distributor_id", "product_id"})})
public class DistributorProductScope extends BaseEntity {

    @Column(nullable = false)
    private UUID distributorId;

    @Column(nullable = false)
    private UUID productId;
}
