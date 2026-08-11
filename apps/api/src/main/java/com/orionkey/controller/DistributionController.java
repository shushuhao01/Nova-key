package com.orionkey.controller;

import com.orionkey.common.ApiResponse;
import com.orionkey.context.RequestContext;
import com.orionkey.service.DistributionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/distribution")
@RequiredArgsConstructor
public class DistributionController {

    private final DistributionService distributionService;

    // ── 公开：推广链接解析 ──
    @GetMapping("/resolve/{linkCode}")
    public ApiResponse<?> resolveLink(@PathVariable String linkCode, HttpServletRequest request) {
        String ip = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return ApiResponse.success(distributionService.resolvePromotionLink(linkCode, ip, userAgent));
    }

    // ── 公开：商品点击埋点（全店推广链接进店后点击商品时上报；也兼容商品链接场景）──
    @PostMapping("/product-click")
    public ApiResponse<?> recordProductClick(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            UUID linkId = body.get("link_id") != null ? UUID.fromString(body.get("link_id").toString()) : null;
            UUID productId = body.get("product_id") != null ? UUID.fromString(body.get("product_id").toString()) : null;
            distributionService.recordProductClick(linkId, productId, getClientIp(request), request.getHeader("User-Agent"));
        } catch (Exception e) {
            // 埋点失败静默，不影响用户操作
            return ApiResponse.success(null);
        }
        return ApiResponse.success(null);
    }

    // ── 公开：佣金预估 ──
    @GetMapping("/commission-preview")
    public ApiResponse<?> commissionPreview(@RequestParam("product_ids") String productIds) {
        UUID userId = null;
        try {
            userId = RequestContext.getUserId();
        } catch (Exception ignored) {
            // 未登录时 userId = null
        }
        List<UUID> ids = Arrays.stream(productIds.split(","))
                .filter(s -> !s.isBlank())
                .map(UUID::fromString)
                .collect(Collectors.toList());
        return ApiResponse.success(distributionService.commissionPreview(userId, ids));
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip != null && ip.contains(",") ? ip.split(",")[0].trim() : ip;
    }
}
