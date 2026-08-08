package com.orionkey.controller;

import com.orionkey.common.ApiResponse;
import com.orionkey.context.RequestContext;
import com.orionkey.service.MarketingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** 前台：优惠券领取与核销校验（营销活动） */
@RestController
@RequestMapping("/marketing")
@RequiredArgsConstructor
public class MarketingController {

    private final MarketingService marketingService;

    /** 领取优惠券：登录用户自动绑定账户；未登录需传 email */
    @PostMapping("/coupons/claim")
    public ApiResponse<?> claimCoupon(@RequestBody Map<String, Object> body) {
        UUID userId = RequestContext.getUserId();
        String code = (String) body.get("code");
        String email = (String) body.get("email");
        return ApiResponse.success(marketingService.claimCoupon(code, userId, email));
    }

    /** 下单页校验优惠券：计算对指定金额的抵扣（amount 传商品总价） */
    @PostMapping("/coupons/validate")
    public ApiResponse<?> validateCoupon(@RequestBody Map<String, Object> body) {
        UUID userId = RequestContext.getUserId();
        String code = (String) body.get("code");
        String email = (String) body.get("email");
        BigDecimal amount = body.get("amount") instanceof Number n
                ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
        return ApiResponse.success(marketingService.validateCoupon(code, userId, email, amount));
    }
}
