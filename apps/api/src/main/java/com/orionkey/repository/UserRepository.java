package com.orionkey.repository;

import com.orionkey.constant.UserRole;
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

    // ── 客户管理（注册客户 = role=USER 的普通用户，内部员工不混入） ──

    long countByRole(UserRole role);

    Page<User> findByRoleOrderByCreatedAtDesc(UserRole role, Pageable pageable);

    Page<User> findByRoleAndUsernameContainingOrRoleAndEmailContaining(
            UserRole r1, String kw1, UserRole r2, String kw2, Pageable pageable);

    /** 统计某时间点之后注册的客户数（role=USER，客户管理「新增」卡片用） */
    long countByRoleAndCreatedAtGreaterThanEqual(UserRole role, LocalDateTime since);

    /** 绑定某角色的员工数（角色删除校验用） */
    long countByRoleId(UUID roleId);

    // ── 内部人员（role != USER：ADMIN + STAFF，系统管理用） ──

    long countByRoleNot(UserRole role);

    Page<User> findByRoleNotOrderByCreatedAtDesc(UserRole role, Pageable pageable);

    Page<User> findByRoleNotAndUsernameContainingOrRoleNotAndEmailContaining(
            UserRole r1, String kw1, UserRole r2, String kw2, Pageable pageable);

    /** 统计某时间点之后注册的用户数（报表用） */
    long countByCreatedAtGreaterThanEqual(LocalDateTime since);

    /** 统计 [from, to) 区间注册的用户数（报表用） */
    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);
}
