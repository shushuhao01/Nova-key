package com.orionkey.controller;

import com.orionkey.annotation.LogOperation;
import com.orionkey.common.ApiResponse;
import com.orionkey.service.AdminCustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** 管理后台：客户管理（注册客户 / 匿名客户） */
@RestController
@RequestMapping("/admin/customers")
@RequiredArgsConstructor
public class AdminCustomerController {

    private final AdminCustomerService adminCustomerService;

    /** 汇总卡片：客户总数 / 新增 / 成交客户 / 未成交客户 */
    @GetMapping("/overview")
    public ApiResponse<?> overview() {
        return ApiResponse.success(adminCustomerService.overview());
    }

    /** 注册客户分页列表 */
    @GetMapping("/registered")
    public ApiResponse<?> listRegistered(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return ApiResponse.success(adminCustomerService.listRegistered(keyword, page, pageSize));
    }

    /** 匿名客户分页列表 */
    @GetMapping("/anonymous")
    public ApiResponse<?> listAnonymous(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return ApiResponse.success(adminCustomerService.listAnonymous(keyword, page, pageSize));
    }

    /** 注册客户详情 */
    @GetMapping("/registered/{id}")
    public ApiResponse<?> registeredDetail(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return ApiResponse.success(adminCustomerService.registeredDetail(id, page, pageSize));
    }

    /** 匿名客户详情（联系方式经 @RequestParam 传递，避免 URL 特殊字符问题；手机号或邮箱） */
    @GetMapping("/anonymous/detail")
    public ApiResponse<?> anonymousDetail(
            @RequestParam String contact,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return ApiResponse.success(adminCustomerService.anonymousDetail(contact, page, pageSize));
    }

    /** 管理员重置客户密码（随机新密码一次性返回，旧 token 立即失效） */
    @LogOperation(action = "user.password.reset", targetType = "CUSTOMER", targetId = "#id", detail = "'管理员重置客户密码'")
    @PostMapping("/registered/{id}/reset-password")
    public ApiResponse<?> resetPassword(@PathVariable UUID id) {
        return ApiResponse.success(adminCustomerService.resetPassword(id));
    }

    /** 封禁 / 解禁注册客户 */
    @LogOperation(action = "customer.toggle", targetType = "CUSTOMER", targetId = "#id", detail = "'封禁/解禁客户'")
    @PostMapping("/registered/{id}/toggle")
    public ApiResponse<Void> toggleRegistered(@PathVariable UUID id, @RequestBody Map<String, Object> request) {
        int isDeleted = ((Number) request.get("is_deleted")).intValue();
        adminCustomerService.toggleRegistered(id, isDeleted);
        return ApiResponse.success();
    }

    /** 解绑客户邮箱 / 手机号（需填写解绑理由，理由写入操作日志） */
    @LogOperation(action = "customer.unbind", targetType = "CUSTOMER", targetId = "#id",
            detail = "'解绑客户' + (#request['field'] == 'email' ? '邮箱' : '手机号') + '，理由：' + #request['reason']")
    @PostMapping("/registered/{id}/unbind")
    public ApiResponse<Void> unbindContact(@PathVariable UUID id, @RequestBody Map<String, Object> request) {
        adminCustomerService.unbindContact(id,
                request.get("field") == null ? null : request.get("field").toString(),
                request.get("reason") == null ? null : request.get("reason").toString());
        return ApiResponse.success();
    }
}
