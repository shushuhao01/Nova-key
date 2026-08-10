package com.orionkey.repository;

import com.orionkey.entity.NotificationTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {

    Optional<NotificationTemplate> findByCode(String code);

    List<NotificationTemplate> findAllByOrderBySortOrderAscCreatedAtAsc();

    /** 模板列表分页 + 分类/启用状态筛选（category 空串按不筛选处理） */
    @Query("SELECT t FROM NotificationTemplate t WHERE " +
            "(:category IS NULL OR :category = '' OR t.category = :category) " +
            "AND (:enabled IS NULL OR t.enabled = :enabled) " +
            "ORDER BY t.sortOrder ASC, t.createdAt ASC")
    Page<NotificationTemplate> findByFilters(@Param("category") String category,
                                             @Param("enabled") Boolean enabled,
                                             Pageable pageable);

    /** 当前最大排序值（新增模板时排到末尾） */
    @Query("SELECT COALESCE(MAX(t.sortOrder), 0) FROM NotificationTemplate t")
    int findMaxSortOrder();
}
