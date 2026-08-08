package com.orionkey.controller;

import com.orionkey.annotation.LogOperation;
import com.orionkey.common.ApiResponse;
import com.orionkey.service.MarketingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** 管理后台：营销管理（营销活动 CRUD + 发送） */
@RestController
@RequestMapping("/admin/marketing")
@RequiredArgsConstructor
public class AdminMarketingController {

    private final MarketingService marketingService;

    @GetMapping("/campaigns")
    public ApiResponse<?> listCampaigns(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return ApiResponse.success(marketingService.listCampaigns(keyword, status, page, pageSize));
    }

    @GetMapping("/campaigns/{id}")
    public ApiResponse<?> getCampaign(@PathVariable UUID id) {
        return ApiResponse.success(marketingService.getCampaign(id));
    }

    @LogOperation(action = "marketing.create", targetType = "MARKETING", detail = "'创建营销活动'")
    @PostMapping("/campaigns")
    public ApiResponse<?> createCampaign(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(marketingService.createCampaign(body));
    }

    @LogOperation(action = "marketing.update", targetType = "MARKETING", targetId = "#id", detail = "'更新营销活动'")
    @PutMapping("/campaigns/{id}")
    public ApiResponse<?> updateCampaign(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(marketingService.updateCampaign(id, body));
    }

    @LogOperation(action = "marketing.delete", targetType = "MARKETING", targetId = "#id", detail = "'删除营销活动'")
    @DeleteMapping("/campaigns/{id}")
    public ApiResponse<Void> deleteCampaign(@PathVariable UUID id) {
        marketingService.deleteCampaign(id);
        return ApiResponse.success();
    }

    @LogOperation(action = "marketing.send", targetType = "MARKETING", targetId = "#id", detail = "'发送营销邮件'")
    @PostMapping("/campaigns/{id}/send")
    public ApiResponse<?> sendCampaign(@PathVariable UUID id) {
        return ApiResponse.success(marketingService.sendCampaign(id));
    }
}
