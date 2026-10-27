package com.orionkey.repository;

import com.orionkey.constant.UserRole;
import com.orionkey.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** 悲观写锁：账号绑定 / 合并时对 current、target 加锁，防止并发重复合并 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByUsernameOrEmail(String username, String email);

    /** 按手机号查找用户（手机号登录 / 注册查重 / 找回密码判定） */
    Optional<User> findByPhone(String phone);

    boolean existsByPhone(String phone);

    /** 按公众号 openid 查找用户（关注/取关事件关联） */
    Optional<User> findByMpOpenid(String mpOpenid);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    Page<User> findByUsernameContainingOrEmailContaining(String username, String email, Pageable pageable);

    // ── 客户管理（注册客户 = role=USER 的普通用户，内部员工不混入） ──

    long countByRole(UserRole role);

    Page<User> findByRoleOrderByCreatedAtDesc(UserRole role, Pageable pageable);

    Page<User> findByRoleAndUsernameContainingOrRoleAndEmailContaining(
            UserRole r1, String kw1, UserRole r2, String kw2, Pageable pageable);

    /** 注册客户搜索：用户名 / 邮箱 / 手机号（客户管理） */
    Page<User> findByRoleAndUsernameContainingOrRoleAndEmailContainingOrRoleAndPhoneContaining(
            UserRole r1, String kw1, UserRole r2, String kw2, UserRole r3, String kw3, Pageable pageable);

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
