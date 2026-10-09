package com.orionkey.controller;

import com.orionkey.dto.TrackRequest;
import com.orionkey.service.VisitService;
import com.orionkey.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/visit")
@RequiredArgsConstructor
public class VisitController {

    private final VisitService visitService;
    private final JwtUtils jwtUtils;

    @PostMapping("/track")
    public ResponseEntity<Void> track(@RequestBody(required = false) TrackRequest body,
                                      HttpServletRequest request) {
        String ip = extractClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        boolean backendUser = isBackendUser(request);
        visitService.track(ip, userAgent, body, backendUser);
        return ResponseEntity.noContent().build();
    }

    /** 判断请求是否来自后台用户（管理员/员工），用于「排除管理员自身访问」 */
    private boolean isBackendUser(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        String token = header.substring(7).trim();
        if (token.isEmpty()) {
            return false;
        }
        Claims claims = jwtUtils.parseTokenSafe(token);
        if (claims == null) {
            return false;
        }
        String role = claims.get("role", String.class);
        return "ADMIN".equalsIgnoreCase(role) || "STAFF".equalsIgnoreCase(role);
    }

    private String extractClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            // X-Forwarded-For 可能包含多个 IP，取第一个（真实客户端 IP）
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        return request.getRemoteAddr();
    }
}
