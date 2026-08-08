package com.orionkey.service;

import com.orionkey.common.PageResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 系统管理（RBAC）：内部人员（ADMIN/STAFF）与角色/权限管理。
 */
public interface AdminSystemService {

    // ── 内部人员管理 ──

    PageResult<?> listStaff(String keyword, int page, int pageSize);

    Map<String, Object> staffDetail(UUID id);

    Map<String, Object> createStaff(Map<String, Object> body);

    Map<String, Object> updateStaff(UUID id, Map<String, Object> body);

    void resetPassword(UUID id, String password);

    void toggleStaff(UUID id, int isDeleted);

    void deleteStaff(UUID id);

    // ── 角色管理 ──

    PageResult<?> listRoles(String keyword, int page, int pageSize);

    Map<String, Object> createRole(Map<String, Object> body);

    Map<String, Object> updateRole(UUID id, Map<String, Object> body);

    void deleteRole(UUID id);

    List<Map<String, String>> listPermissions();
}
