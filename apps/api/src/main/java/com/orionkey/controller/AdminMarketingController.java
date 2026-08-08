package com.orionkey.controller;

import com.orionkey.annotation.LogOperation;
import com.orionkey.common.ApiResponse;
import com.orionkey.service.MarketingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** 管理后台：营销管理（优惠券管理 + 营销邮件，独立 TAB 对应独立接口） */
@RestController
@RequestMapping("/admin/marketing")
@RequiredArgsConstructor
public class AdminMarketingController {

    private final MarketingService marketingService;

    // ═══════════ 优惠券管理 ═══════════

    @GetMapping("/coupons")
    public ApiResponse<?> listCoupons(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return ApiResponse.success(marketingService.listCoupons(keyword, page, pageSize));
    }

    @GetMapping("/coupons/{id}")
    public ApiResponse<?> getCoupon(@PathVariable UUID id) {
        return ApiResponse.success(marketingService.getCoupon(id));
    }

    @LogOperation(action = "marketing.coupon.create", targetType = "MARKETING", detail = "'创建优惠券'")
    @PostMapping("/coupons")
    public ApiResponse<?> createCoupon(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(marketingService.createCoupon(body));
    }

    @LogOperation(action = "marketing.coupon.update", targetType = "MARKETING", targetId = "#id", detail = "'编辑优惠券'")
    @PutMapping("/coupons/{id}")
    public ApiResponse<?> updateCoupon(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(marketingService.updateCoupon(id, body));
    }

    @LogOperation(action = "marketing.coupon.cancel", targetType = "MARKETING", targetId = "#id", detail = "'作废优惠券'")
    @PostMapping("/coupons/{id}/cancel")
    public ApiResponse<Void> cancelCoupon(@PathVariable UUID id) {
        marketingService.cancelCoupon(id);
        return ApiResponse.success();
    }

    @LogOperation(action = "marketing.coupon.delete", targetType = "MARKETING", targetId = "#id", detail = "'删除优惠券'")
    @DeleteMapping("/coupons/{id}")
    public ApiResponse<Void> deleteCoupon(@PathVariable UUID id) {
        marketingService.deleteCoupon(id);
        return ApiResponse.success();
    }

    // ═══════════ 营销邮件 ═══════════

    @GetMapping("/emails")
    public ApiResponse<?> listEmails(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return ApiResponse.success(marketingService.listEmailCampaigns(keyword, status, page, pageSize));
    }

    @GetMapping("/emails/{id}")
    public ApiResponse<?> getEmail(@PathVariable UUID id) {
        return ApiResponse.success(marketingService.getEmailCampaign(id));
    }

    @LogOperation(action = "marketing.email.create", targetType = "MARKETING", detail = "'创建营销邮件'")
    @PostMapping("/emails")
    public ApiResponse<?> createEmail(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(marketingService.createEmailCampaign(body));
    }

    @LogOperation(action = "marketing.email.update", targetType = "MARKETING", targetId = "#id", detail = "'编辑营销邮件'")
    @PutMapping("/emails/{id}")
    public ApiResponse<?> updateEmail(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(marketingService.updateEmailCampaign(id, body));
    }

    @LogOperation(action = "marketing.email.delete", targetType = "MARKETING", targetId = "#id", detail = "'删除营销邮件'")
    @DeleteMapping("/emails/{id}")
    public ApiResponse<Void> deleteEmail(@PathVariable UUID id) {
        marketingService.deleteEmailCampaign(id);
        return ApiResponse.success();
    }

    @LogOperation(action = "marketing.email.send", targetType = "MARKETING", targetId = "#id", detail = "'发送营销邮件'")
    @PostMapping("/emails/{id}/send")
    public ApiResponse<?> sendEmail(@PathVariable UUID id) {
        return ApiResponse.success(marketingService.sendEmailCampaign(id));
    }

    /** 发送用户弹窗：收件人分页列表 + 送达统计 */
    @GetMapping("/emails/{id}/recipients")
    public ApiResponse<?> recipients(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return ApiResponse.success(marketingService.campaignRecipients(id, page, pageSize));
    }

    // ═══════════ 兼容旧版 /admin/marketing/campaigns 路由（委托给营销邮件） ═══════════

    @Deprecated
    @GetMapping("/campaigns")
    public ApiResponse<?> listCampaigns(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return ApiResponse.success(marketingService.listEmailCampaigns(keyword, status, page, pageSize));
    }

    @Deprecated
    @GetMapping("/campaigns/{id}")
    public ApiResponse<?> getCampaign(@PathVariable UUID id) {
        return ApiResponse.success(marketingService.getEmailCampaign(id));
    }

    @Deprecated
    @PostMapping("/campaigns")
    public ApiResponse<?> createCampaign(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(marketingService.createEmailCampaign(body));
    }

    @Deprecated
    @PutMapping("/campaigns/{id}")
    public ApiResponse<?> updateCampaign(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(marketingService.updateEmailCampaign(id, body));
    }

    @Deprecated
    @DeleteMapping("/campaigns/{id}")
    public ApiResponse<Void> deleteCampaign(@PathVariable UUID id) {
        marketingService.deleteEmailCampaign(id);
        return ApiResponse.success();
    }

    @Deprecated
    @PostMapping("/campaigns/{id}/send")
    public ApiResponse<?> sendCampaign(@PathVariable UUID id) {
        return ApiResponse.success(marketingService.sendEmailCampaign(id));
    }
}
