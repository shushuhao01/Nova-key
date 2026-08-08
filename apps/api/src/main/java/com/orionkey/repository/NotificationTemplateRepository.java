package com.orionkey.repository;

import com.orionkey.entity.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {

    Optional<NotificationTemplate> findByCode(String code);

    List<NotificationTemplate> findAllByOrderBySortOrderAscCreatedAtAsc();
}
