package com.orionkey.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.constant.PermissionConst;
import com.orionkey.constant.UserRole;
import com.orionkey.entity.User;
import com.orionkey.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * RBAC 权限解析：根据用户角色计算其拥有的权限码集合。
 * - role=ADMIN → 全部权限
 * - role=STAFF → 按 role_id 关联角色的 permissions
 * - 其他 → 空
 */
@Component
@RequiredArgsConstructor
public class PermissionResolver {

    private final RoleRepository roleRepository;
    private final ObjectMapper objectMapper;

    public List<String> resolve(User user) {
        if (user == null) {
            return List.of();
        }
        if (user.getRole() == UserRole.ADMIN) {
            return PermissionConst.ALL;
        }
        if (user.getRole() == UserRole.STAFF && user.getRoleId() != null) {
            return roleRepository.findById(user.getRoleId())
                    .map(r -> parse(r.getPermissions()))
                    .orElse(List.of());
        }
        return List.of();
    }

    /** 是否拥有后台访问总权限 */
    public boolean hasBackendAccess(User user) {
        return resolve(user).contains(PermissionConst.BACKEND_ACCESS);
    }

    /** 解析权限 JSON 为权限码列表 */
    public List<String> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return list.stream().filter(Objects::nonNull).distinct().toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
