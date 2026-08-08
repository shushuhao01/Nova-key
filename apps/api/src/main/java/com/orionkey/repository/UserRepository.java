package com.orionkey.repository;

import com.orionkey.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByUsernameOrEmail(String username, String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    Page<User> findByUsernameContainingOrEmailContaining(String username, String email, Pageable pageable);

    /** 统计某时间点之后注册的用户数（报表用） */
    long countByCreatedAtGreaterThanEqual(LocalDateTime since);

    /** 统计 [from, to) 区间注册的用户数（报表用） */
    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);
}
