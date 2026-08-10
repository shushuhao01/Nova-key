package com.orionkey.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.common.PageResult;
import com.orionkey.constant.ErrorCode;
import com.orionkey.constant.PermissionConst;
import com.orionkey.constant.UserRole;
import com.orionkey.context.RequestContext;
import com.orionkey.entity.Role;
import com.orionkey.entity.User;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.RoleRepository;
import com.orionkey.repository.UserRepository;
import com.orionkey.service.AdminSystemService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSystemServiceImpl implements AdminSystemService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final PermissionResolver permissionResolver;

    /** 内置超级管理员角色编码 */
    private static final String SUPER_ADMIN_CODE = "SUPER_ADMIN";

    @PostConstruct
    public void initSuperAdminRole() {
        try {
            if (!roleRepository.existsByCode(SUPER_ADMIN_CODE)) {
                Role r = new Role();
                r.setCode(SUPER_ADMIN_CODE);
                r.setName("超级管理员");
                r.setDescription("系统内置角色，拥有全部后台权限");
                r.setPermissions(toJson(PermissionConst.ALL));
                r.setIsSystem(1);
                roleRepository.save(r);
                log.info("Initialized built-in role {}", SUPER_ADMIN_CODE);
            }
            // 将历史遗留的内置 ADMIN 用户（未绑定角色）自动绑定到超级管理员角色
            Role superAdmin = roleRepository.findAll().stream()
                    .filter(r -> SUPER_ADMIN_CODE.equals(r.getCode()))
                    .findFirst().orElse(null);
            if (superAdmin != null) {
                for (User u : userRepository.findByRoleOrderByCreatedAtDesc(UserRole.ADMIN, Pageable.unpaged()).getContent()) {
                    if (u.getRoleId() == null) {
                        u.setRoleId(superAdmin.getId());
                        userRepository.save(u);
                        log.info("Bound built-in admin {} to SUPER_ADMIN role", u.getUsername());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Init super admin role failed: {}", e.getMessage(), e);
        }
    }

    // ═══════════ 内部人员管理 ═══════════

    @Override
    @Transactional(readOnly = true)
    public PageResult<?> listStaff(String keyword, int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<User> p;
        if (keyword != null && !keyword.isBlank()) {
            p = userRepository.findByRoleNotAndUsernameContainingOrRoleNotAndEmailContaining(
                    UserRole.USER, keyword.trim(), UserRole.USER, keyword.trim(), pageable);
        } else {
            p = userRepository.findByRoleNotOrderByCreatedAtDesc(UserRole.USER, pageable);
        }
        List<Map<String, Object>> list = p.getContent().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("email", u.getEmail());
            m.put("role", u.getRole().name());
            m.put("role_id", u.getRoleId());
            m.put("role_name", roleName(u.getRoleId()));
            m.put("is_deleted", u.getIsDeleted());
            m.put("created_at", u.getCreatedAt());
            return m;
        }).toList();
        return PageResult.of(p, list);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> staffDetail(UUID id) {
        User u = requireStaff(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("email", u.getEmail());
        m.put("role", u.getRole().name());
        m.put("role_id", u.getRoleId());
        m.put("role_name", roleName(u.getRoleId()));
        m.put("is_deleted", u.getIsDeleted());
        m.put("created_at", u.getCreatedAt());
        m.put("permissions", permissionResolver.resolve(u));
        return m;
    }

    @Override
    @Transactional
    public Map<String, Object> createStaff(Map<String, Object> body) {
        String username = str(body.get("username"));
        String email = str(body.get("email"));
        String password = str(body.get("password"));
        String roleIdStr = str(body.get("role_id"));
        if (username == null || username.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名不能为空");
        }
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱不能为空");
        }
        if (password == null || password.length() < 6) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码至少 6 位");
        }
        if (roleIdStr == null || roleIdStr.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择角色");
        }
        UUID roleId;
        try {
            roleId = UUID.fromString(roleIdStr);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色参数无效");
        }
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "角色不存在"));
        String uName = username.trim();
        if (userRepository.existsByUsername(uName)) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS, "用户名已存在");
        }
        String e = email.trim().toLowerCase();
        if (userRepository.existsByEmail(e)) {
            throw new BusinessException(ErrorCode.EMAIL_EXISTS, "该邮箱已使用");
        }
        User user = new User();
        user.setUsername(uName);
        user.setEmail(e);
        user.setPasswordHash(passwordEncoder.encode(password));
        // 绑定超级管理员角色即视为内置管理员；否则为普通员工（与 updateStaff 一致）
        user.setRole(SUPER_ADMIN_CODE.equals(role.getCode()) ? UserRole.ADMIN : UserRole.STAFF);
        user.setRoleId(role.getId());
        userRepository.save(user);
        log.info("Created staff {} (role={}) by {}", user.getUsername(), role.getCode(), RequestContext.getUserId());
        return Map.of("id", user.getId());
    }

    @Override
    @Transactional
    public Map<String, Object> updateStaff(UUID id, Map<String, Object> body) {
        User u = requireStaff(id);
        UUID current = RequestContext.getUserId();
        // 不能修改自己的角色（防止自我降权/提权）
        if (u.getId().equals(current) && body.containsKey("role_id")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能修改自己的角色");
        }
        if (body.containsKey("role_id")) {
            String roleIdStr = str(body.get("role_id"));
            if (roleIdStr == null || roleIdStr.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择角色");
            }
            UUID roleId;
            try {
                roleId = UUID.fromString(roleIdStr);
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "角色参数无效");
            }
            Role role = roleRepository.findById(roleId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "角色不存在"));
            // 系统管理员绑定超级管理员角色时升级为内置 ADMIN 枚举；否则为 STAFF
            if (SUPER_ADMIN_CODE.equals(role.getCode())) {
                u.setRole(UserRole.ADMIN);
            } else {
                u.setRole(UserRole.STAFF);
            }
            u.setRoleId(role.getId());
        }
        if (body.containsKey("username")) {
            String username = str(body.get("username"));
            if (username == null || username.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名不能为空");
            }
            User dup = userRepository.findByUsername(username.trim()).orElse(null);
            if (dup != null && !dup.getId().equals(id)) {
                throw new BusinessException(ErrorCode.USERNAME_EXISTS, "用户名已存在");
            }
            u.setUsername(username.trim());
        }
        userRepository.save(u);
        return staffDetail(id);
    }

    @Override
    @Transactional
    public void resetPassword(UUID id, String password) {
        User u = requireStaff(id);
        if (password == null || password.length() < 6) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码至少 6 位");
        }
        u.setPasswordHash(passwordEncoder.encode(password));
        // 密码版本 +1：已签发的旧 JWT 立即失效（需重新登录）
        u.setPasswordVersion(u.getPasswordVersion() + 1);
        // 重置后清除锁定与失败计数
        u.setFailedLoginAttempts(0);
        u.setLockUntil(null);
        userRepository.save(u);
        log.info("Reset password for staff {} by {}", u.getUsername(), RequestContext.getUserId());
    }

    @Override
    @Transactional
    public void toggleStaff(UUID id, int isDeleted) {
        if (isDeleted != 0 && isDeleted != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "is_deleted 参数只能为 0 或 1");
        }
        User u = requireStaff(id);
        if (u.getId().equals(RequestContext.getUserId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能禁用/解禁自己的账号");
        }
        if (u.getRole() == UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "内置管理员账号不可禁用");
        }
        u.setIsDeleted(isDeleted);
        userRepository.save(u);
    }

    @Override
    @Transactional
    public void deleteStaff(UUID id) {
        User u = requireStaff(id);
        if (u.getId().equals(RequestContext.getUserId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能删除自己的账号");
        }
        if (u.getRole() == UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "内置管理员账号不可删除");
        }
        userRepository.delete(u);
        log.info("Deleted staff {} by {}", u.getUsername(), RequestContext.getUserId());
    }

    // ═══════════ 角色管理 ═══════════

    @Override
    @Transactional(readOnly = true)
    public PageResult<?> listRoles(String keyword, int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100),
                Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<Role> p;
        if (keyword != null && !keyword.isBlank()) {
            p = roleRepository.findAll(pageable); // 角色数量少，仅过滤名称/编码由前端做或直接全量返回
        } else {
            p = roleRepository.findAll(pageable);
        }
        List<Map<String, Object>> list = p.getContent().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("code", r.getCode());
            m.put("name", r.getName());
            m.put("description", r.getDescription());
            m.put("permissions", parse(r.getPermissions()));
            m.put("is_system", r.getIsSystem());
            m.put("user_count", userRepository.countByRoleId(r.getId()));
            m.put("created_at", r.getCreatedAt());
            return m;
        }).toList();
        return PageResult.of(p, list);
    }

    @Override
    @Transactional
    public Map<String, Object> createRole(Map<String, Object> body) {
        String code = str(body.get("code"));
        String name = str(body.get("name"));
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色编码不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色名称不能为空");
        }
        String c = code.trim().toUpperCase();
        if (roleRepository.existsByCode(c)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色编码已存在");
        }
        Role r = new Role();
        r.setCode(c);
        r.setName(name.trim());
        r.setDescription(str(body.get("description")));
        r.setPermissions(toJson(parsePermissions(body.get("permissions"))));
        r.setIsSystem(0);
        roleRepository.save(r);
        return Map.of("id", r.getId());
    }

    @Override
    @Transactional
    public Map<String, Object> updateRole(UUID id, Map<String, Object> body) {
        Role r = roleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "角色不存在"));
        if (r.getIsSystem() == 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "内置角色不可修改");
        }
        if (body.containsKey("name")) {
            String name = str(body.get("name"));
            if (name == null || name.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "角色名称不能为空");
            }
            r.setName(name.trim());
        }
        if (body.containsKey("description")) {
            r.setDescription(str(body.get("description")));
        }
        if (body.containsKey("permissions")) {
            r.setPermissions(toJson(parsePermissions(body.get("permissions"))));
        }
        roleRepository.save(r);
        return roleMap(r);
    }

    @Override
    @Transactional
    public void deleteRole(UUID id) {
        Role r = roleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "角色不存在"));
        if (r.getIsSystem() == 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "内置角色不可删除");
        }
        if (userRepository.countByRoleId(id) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该角色下仍有员工，请先解绑后再删除");
        }
        roleRepository.delete(r);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, String>> listPermissions() {
        return PermissionConst.CATALOG;
    }

    // ═══════════ 辅助 ═══════════

    private User requireStaff(UUID id) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "员工不存在"));
        if (u.getRole() == UserRole.USER) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该用户不是内部人员");
        }
        return u;
    }

    private String roleName(UUID roleId) {
        if (roleId == null) return null;
        return roleRepository.findById(roleId).map(Role::getName).orElse(null);
    }

    private Map<String, Object> roleMap(Role r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("code", r.getCode());
        m.put("name", r.getName());
        m.put("description", r.getDescription());
        m.put("permissions", parse(r.getPermissions()));
        m.put("is_system", r.getIsSystem());
        m.put("user_count", userRepository.countByRoleId(r.getId()));
        m.put("created_at", r.getCreatedAt());
        return m;
    }

    private List<String> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            }).stream().filter(Objects::nonNull).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parsePermissions(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(Objects::toString).filter(s -> !s.isBlank()).distinct().toList();
        }
        return List.of();
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
