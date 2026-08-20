package com.testagent.security;

import com.testagent.entity.User;
import com.testagent.repository.UserRepository;
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
            // 2. SSE：短期 ticket（EventSource 无法设置 Authorization 头）
            if (username == null) {
                String[] principal = sseTicketService.authenticate(request.getParameter("ticket"));
                if (principal != null) {
                    username = principal[0];
                    role = principal[1];
                }
            }
            // 3. 仅媒体访问（video/file 等）保留 ?token= 兼容，SSE 不再接受长期 JWT
            if (username == null && isMediaPath(request.getRequestURI())) {
                String param = request.getParameter("token");
                if (param != null && jwtUtil.isValid(param)) {
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
}
