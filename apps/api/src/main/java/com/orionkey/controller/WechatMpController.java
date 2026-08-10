package com.orionkey.controller;

import com.orionkey.common.ApiResponse;
import com.orionkey.service.WechatMpConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开：公众号关注信息（关注二维码），分销中心用于引导关注公众号。
 * 仅返回二维码与是否已配置，不含任何密钥。
 */
@RestController
@RequestMapping("/wechat-mp")
@RequiredArgsConstructor
public class WechatMpController {

    private final WechatMpConfigService wechatMpConfigService;

    @GetMapping("/follow-info")
    public ApiResponse<?> followInfo() {
        return ApiResponse.success(wechatMpConfigService.getFollowInfo());
    }
}
