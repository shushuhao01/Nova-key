package com.orionkey.controller;

import com.orionkey.common.ApiResponse;
import com.orionkey.service.SiteConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SiteConfigController {

    private final SiteConfigService siteConfigService;

    @GetMapping("/site/config")
    public ApiResponse<?> getPublicConfig() {
        return ApiResponse.success(siteConfigService.getPublicConfig());
    }

    /** 客户配置聚合读取（含默认密码与客服提示，需登录，不对外公开） */
    @GetMapping("/site/config/customer")
    public ApiResponse<?> getCustomerConfig() {
        return ApiResponse.success(siteConfigService.getCustomerConfig());
    }
}
