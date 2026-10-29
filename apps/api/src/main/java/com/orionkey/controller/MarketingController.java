package com.orionkey.controller;

import com.orionkey.common.ApiResponse;
import com.orionkey.context.RequestContext;
import com.orionkey.service.MarketingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 前台：优惠券领取与核销校验（营销活动） */
@RestController
@RequestMapping("/marketing")
@RequiredArgsConstructor
public class MarketingController {

    /** 1x1 透明 GIF（邮件追踪像素） */
    private static final byte[] TRACKING_PIXEL =
            Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");

    private final MarketingService marketingService;

    /** 邮件追踪像素：客户端加载图片即记为已打开，返回 1x1 透明图 */
    @GetMapping("/track/open")
    public ResponseEntity<byte[]> trackOpen(@RequestParam(value = "rid", required = false) String rid) {
        marketingService.recordEmailOpen(rid);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .contentType(MediaType.IMAGE_GIF)
                .body(TRACKING_PIXEL);
    }

    /** 邮件内链接追踪：记录点击后 302 跳转到站内路径 */
    @GetMapping("/track/click")
    public ResponseEntity<Void> trackClick(@RequestParam(value = "rid", required = false) String rid,
                                           @RequestParam(value = "p", required = false) String path) {
        String target = marketingService.recordEmailClick(rid, path);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    /** 领取优惠券：登录用户自动绑定账户；未登录需传 email */
    @PostMapping("/coupons/claim")
    public ApiResponse<?> claimCoupon(@RequestBody Map<String, Object> body) {
        UUID userId = RequestContext.getUserId();
        String code = (String) body.get("code");
        String email = (String) body.get("email");
        return ApiResponse.success(marketingService.claimCoupon(code, userId, email));
    }

    /** 公开查询优惠券信息（领取页展示用，无需登录） */
    @GetMapping("/coupons/info")
    public ApiResponse<?> couponInfo(@RequestParam String code) {
        return ApiResponse.success(marketingService.couponInfo(code));
    }

    /** 个人中心：我的优惠券（status=ALL/CLAIMED/USED/EXPIRED） */
    @GetMapping("/coupons/my")
    public ApiResponse<?> myCoupons(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        UUID userId = RequestContext.getUserId();
        if (userId == null) {
            return ApiResponse.success(com.orionkey.common.PageResult.of(List.of(), page, pageSize, 0));
        }
        return ApiResponse.success(marketingService.myCoupons(userId, status, page, pageSize));
    }

    /** 下单页校验优惠券：计算对指定金额的抵扣（amount 传商品总价，product_ids 传订单商品列表） */
    @PostMapping("/coupons/validate")
    public ApiResponse<?> validateCoupon(@RequestBody Map<String, Object> body) {
        UUID userId = RequestContext.getUserId();
        String code = (String) body.get("code");
        String email = (String) body.get("email");
        BigDecimal amount = body.get("amount") instanceof Number n
                ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
        List<UUID> productIds = new ArrayList<>();
        if (body.get("product_ids") instanceof List<?> ids) {
            for (Object o : ids) {
                if (o instanceof String s) {
                    try {
                        productIds.add(UUID.fromString(s));
                    } catch (IllegalArgumentException ignored) {
                        // 忽略非法 ID
                    }
                }
            }
        }
        if (productIds.isEmpty() && body.get("product_id") instanceof String pid) {
            try {
                productIds.add(UUID.fromString(pid));
            } catch (IllegalArgumentException ignored) {
                // 忽略非法 ID
            }
        }
        return ApiResponse.success(marketingService.validateCoupon(code, userId, email, amount, productIds));
    }
}
