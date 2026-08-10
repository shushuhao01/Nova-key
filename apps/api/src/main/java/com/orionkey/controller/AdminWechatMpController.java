package com.orionkey.controller;

import com.orionkey.annotation.LogOperation;
import com.orionkey.common.ApiResponse;
import com.orionkey.service.WechatMpConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 微信公众号（服务号）配置管理：AppID/AppSecret/关注二维码/消息模板 + 测试连接。
 * 配置后分销员可在分销中心扫码绑定微信（提现收款），并引导关注公众号。
 */
@RestController
@RequestMapping("/admin/wechat-mp")
@RequiredArgsConstructor
public class AdminWechatMpController {

    private final WechatMpConfigService wechatMpConfigService;

    @GetMapping("/config")
    public ApiResponse<?> getConfig() {
        return ApiResponse.success(wechatMpConfigService.getConfig());
    }

    @LogOperation(action = "wechat_mp.save", targetType = "WECHAT_MP_CONFIG", detail = "'保存公众号配置'")
    @PutMapping("/config")
    public ApiResponse<Void> updateConfig(@RequestBody Map<String, Object> body) {
        wechatMpConfigService.updateConfig(body);
        return ApiResponse.success();
    }

    @LogOperation(action = "wechat_mp.test", targetType = "WECHAT_MP_CONFIG", detail = "'测试公众号配置'")
    @PostMapping("/test")
    public ApiResponse<?> test() {
        return ApiResponse.success(wechatMpConfigService.testConfig());
    }
}
