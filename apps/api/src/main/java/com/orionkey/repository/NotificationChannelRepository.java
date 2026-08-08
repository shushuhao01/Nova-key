package com.orionkey.repository;

import com.orionkey.entity.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, UUID> {

    Optional<NotificationChannel> findByChannelType(String channelType);

    List<NotificationChannel> findAllByOrderBySortOrderAsc();
}
