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
    @Query(value = "SELECT * FROM notification_templates t WHERE " +
            "(CAST(:category AS text) IS NULL OR CAST(:category AS text) = '' OR t.category = CAST(:category AS text)) " +
            "AND (CAST(:enabled AS boolean) IS NULL OR t.is_enabled = CAST(:enabled AS boolean)) " +
            "ORDER BY t.sort_order ASC, t.created_at ASC",
            countQuery = "SELECT COUNT(*) FROM notification_templates t WHERE " +
            "(CAST(:category AS text) IS NULL OR CAST(:category AS text) = '' OR t.category = CAST(:category AS text)) " +
            "AND (CAST(:enabled AS boolean) IS NULL OR t.is_enabled = CAST(:enabled AS boolean))",
            nativeQuery = true)
    Page<NotificationTemplate> findByFilters(@Param("category") String category,
                                             @Param("enabled") Boolean enabled,
                                             Pageable pageable);

    /** 当前最大排序值（新增模板时排到末尾） */
    @Query("SELECT COALESCE(MAX(t.sortOrder), 0) FROM NotificationTemplate t")
    int findMaxSortOrder();
}
