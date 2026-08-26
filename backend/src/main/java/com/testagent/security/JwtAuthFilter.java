package com.testagent.security;

import com.testagent.entity.User;
import com.testagent.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * v4.0: Bearer Token 解析过滤器。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SseTicketService sseTicketService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String username = null;
            String role = null;
            // 1. 首选 Authorization: Bearer
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                if (jwtUtil.isValid(token)) {
                    username = jwtUtil.extractUsername(token);
                    role = jwtUtil.extractRole(token);
                }
            }
            // 2. v8.9.4(12.10): 短期 ticket 仅在白名单路径接受（SSE 流式 + 媒体）——
            // 此前对所有路径生效，票据 300s TTL 内泄露可调用全部 API
            if (username == null && isTicketAcceptedPath(request.getRequestURI())) {
                String[] principal = sseTicketService.authenticate(request.getParameter("ticket"));
                if (principal != null) {
                    username = principal[0];
                    role = principal[1];
                }
            }
            // 3. v8.9.4(12.10): 媒体 ?token=（长期 JWT）进入废弃期——保留可用但每次 WARN，
            // 废弃期结束后移除该分支；新代码一律改用短期 ticket（前端已同步切换）
            if (username == null && isMediaPath(request.getRequestURI())) {
                String param = request.getParameter("token");
                if (param != null && jwtUtil.isValid(param)) {
                    log.warn("[deprecated] 媒体路径使用长期 JWT ?token=（{}），请改用短期 ticket，废弃期后将移除该分支",
                            request.getRequestURI());
                    username = jwtUtil.extractUsername(param);
                    role = jwtUtil.extractRole(param);
                }
            }
            if (username != null) {
                User user = userRepository.findByUsername(username).orElse(null);
                if (user != null) {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    username,
                                    null,
                                    List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isMediaPath(String uri) {
        return uri != null && uri.startsWith("/api/executions/")
                && (uri.endsWith("/video") || uri.endsWith("/file")
                || uri.endsWith("/report"));
    }

    // v8.9.4(12.10): ticket 接受白名单——SSE 流式端点 + 媒体端点；其余路径忽略该参数
    private boolean isTicketAcceptedPath(String uri) {
        if (uri == null) {
            return false;
        }
        if (uri.endsWith("/analyze-stream") || uri.contains("generate-stream")) {
            return true;
        }
        return isMediaPath(uri);
    }
}
