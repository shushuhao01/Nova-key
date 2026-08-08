package com.orionkey.controller;

import com.orionkey.annotation.LogOperation;
import com.orionkey.common.ApiResponse;
import com.orionkey.common.PageResult;
import com.orionkey.service.AdminSystemService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 系统管理（RBAC）：内部人员（ADMIN/STAFF）管理 + 角色/权限配置。
 * 仅超级管理员可访问（SecurityConfig：/admin/system/** 需 SYSTEM_MANAGE 权限）。
 */
@RestController
@RequestMapping("/admin/system")
@RequiredArgsConstructor
public class AdminSystemController {

    private final AdminSystemService adminSystemService;

    // ═══════════ 内部人员 ═══════════

    @GetMapping("/users")
    public ApiResponse<PageResult<?>> listStaff(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return ApiResponse.success(adminSystemService.listStaff(keyword, page, pageSize));
    }

    @GetMapping("/users/{id}")
    public ApiResponse<?> staffDetail(@PathVariable UUID id) {
        return ApiResponse.success(adminSystemService.staffDetail(id));
    }

    @LogOperation(action = "staff.create", targetType = "USER", detail = "'创建内部员工'")
    @PostMapping("/users")
    public ApiResponse<?> createStaff(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(adminSystemService.createStaff(body));
    }

    @LogOperation(action = "staff.update", targetType = "USER", targetId = "#id", detail = "'编辑内部员工'")
    @PutMapping("/users/{id}")
    public ApiResponse<?> updateStaff(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(adminSystemService.updateStaff(id, body));
    }

    @LogOperation(action = "staff.password", targetType = "USER", targetId = "#id", detail = "'重置员工密码'")
    @PostMapping("/users/{id}/password")
    public ApiResponse<Void> resetPassword(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        adminSystemService.resetPassword(id, (String) body.get("password"));
        return ApiResponse.success();
    }

    @LogOperation(action = "staff.toggle", targetType = "USER", targetId = "#id", detail = "'切换员工状态'")
    @PostMapping("/users/{id}/toggle")
    public ApiResponse<Void> toggleStaff(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        adminSystemService.toggleStaff(id, ((Number) body.get("is_deleted")).intValue());
        return ApiResponse.success();
    }

    @LogOperation(action = "staff.delete", targetType = "USER", targetId = "#id", detail = "'删除内部员工'")
    @DeleteMapping("/users/{id}")
    public ApiResponse<Void> deleteStaff(@PathVariable UUID id) {
        adminSystemService.deleteStaff(id);
        return ApiResponse.success();
    }

    // ═══════════ 角色 ═══════════

    @GetMapping("/roles")
    public ApiResponse<PageResult<?>> listRoles(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return ApiResponse.success(adminSystemService.listRoles(keyword, page, pageSize));
    }

    @LogOperation(action = "role.create", targetType = "ROLE", detail = "'创建角色'")
    @PostMapping("/roles")
    public ApiResponse<?> createRole(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(adminSystemService.createRole(body));
    }

    @LogOperation(action = "role.update", targetType = "ROLE", targetId = "#id", detail = "'编辑角色'")
    @PutMapping("/roles/{id}")
    public ApiResponse<?> updateRole(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(adminSystemService.updateRole(id, body));
    }

    @LogOperation(action = "role.delete", targetType = "ROLE", targetId = "#id", detail = "'删除角色'")
    @DeleteMapping("/roles/{id}")
    public ApiResponse<Void> deleteRole(@PathVariable UUID id) {
        adminSystemService.deleteRole(id);
        return ApiResponse.success();
    }

    @GetMapping("/permissions")
    public ApiResponse<?> listPermissions() {
        return ApiResponse.success(adminSystemService.listPermissions());
    }
}
