package com.orionkey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * 推广点击记录
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "distribution_clicks")
public class DistributionClick extends BaseEntity {

    private UUID distributorId;

    private UUID promotionLinkId;

    private UUID productId;

    @Column(length = 45)
    private String ip;

    @Column(length = 500)
    private String userAgent;
}
