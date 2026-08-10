package com.orionkey.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orionkey.common.ApiResponse;
import com.orionkey.constant.ErrorCode;
import com.orionkey.context.RequestContext;
import com.orionkey.entity.User;
import com.orionkey.repository.UserRepository;
import com.orionkey.service.impl.PermissionResolver;
import com.orionkey.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final PermissionResolver permissionResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (StringUtils.hasText(token)) {
                Claims claims = jwtUtils.parseTokenSafe(token);
                if (claims != null) {
                    UUID userId = UUID.fromString(claims.getSubject());
                    String username = claims.get("username", String.class);
                    String role = claims.get("role", String.class);
                    Integer pwdVer = claims.get("pwdVer", Integer.class);

                    // 后台请求（ADMIN/STAFF）：必须校验数据库中用户状态和角色，并按角色注入动态权限码（RBAC）
                    // /api/upload 上传接口（商品图/邮件图/支付证书）同样需要 BACKEND_ACCESS 等后台权限
                    String path = request.getRequestURI();
                    boolean backendRequest = path.startsWith("/api/admin") || path.startsWith("/api/upload");
                    if (backendRequest && ("ADMIN".equals(role) || "STAFF".equals(role))) {
                        User user = userRepository.findById(userId).orElse(null);
                        if (user == null || user.getIsDeleted() == 1
                                || !user.getRole().name().equals(role)) {
                            log.warn("Backend JWT verification failed: userId={}, dbExists={}, dbDeleted={}, dbRole={}",
                                    userId, user != null, user != null ? user.getIsDeleted() : "N/A",
                                    user != null ? user.getRole() : "N/A");
                            rejectRequest(response, "身份验证失败，请重新登录");
                            return;
                        }
                        // 密码版本校验：修改/重置密码后，旧 token 立即失效（pwdVer 为 null 表示升级前签发的旧 token，兼容放行）
                        if (pwdVer != null && user.getPasswordVersion() != pwdVer) {
                            log.warn("Backend JWT password version mismatch: userId={}, tokenVer={}, dbVer={}",
                                    userId, pwdVer, user.getPasswordVersion());
                            rejectRequest(response, "密码已修改，请重新登录");
                            return;
                        }
                        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                        permissionResolver.resolve(user)
                                .forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
                        RequestContext.set(new RequestContext.UserInfo(userId, username, role));
                        SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken(userId, null, authorities));
                        filterChain.doFilter(request, response);
                        return;
                    }

                    // 前台等普通请求：带密码版本号的 token 需比对，确保修改密码后旧会话立即失效
                    if (pwdVer != null) {
                        User user = userRepository.findById(userId).orElse(null);
                        if (user == null || user.getIsDeleted() == 1 || user.getPasswordVersion() != pwdVer) {
                            log.warn("JWT password version mismatch: userId={}, tokenVer={}, dbVer={}",
                                    userId, pwdVer, user != null ? user.getPasswordVersion() : "N/A");
                            rejectRequest(response, "身份验证失败，请重新登录");
                            return;
                        }
                        // 使用数据库最新信息，避免旧 token 中的陈旧用户名/角色
                        username = user.getUsername();
                        role = user.getRole().name();
                    }

                    RequestContext.set(new RequestContext.UserInfo(userId, username, role));
                    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
                    var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    private void rejectRequest(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(ErrorCode.UNAUTHORIZED, message)));
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
