package com.orionkey.repository;

import com.orionkey.entity.DistributionRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DistributionRuleRepository extends JpaRepository<DistributionRule, UUID> {
    /** 获取唯一的规则行 */
    default Optional<DistributionRule> getRule() {
        return findAll().stream().findFirst();
    }
}
