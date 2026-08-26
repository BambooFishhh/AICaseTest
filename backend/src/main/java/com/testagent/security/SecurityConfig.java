package com.testagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v4.0: 安全配置——无状态 JWT 认证。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private JwtAuthFilter jwtAuthFilter;

    @Autowired
    private ObjectMapper objectMapper;

    // v8.9.1(12.4): MCP 来源白名单过滤器——反代场景可开 APP_MCP_TRUST_PROXY 取 XFF 首跳
    @Value("${app.mcp.allowed-remote-addrs:}")
    private java.util.List<String> mcpAllowedRemoteAddrs = new java.util.ArrayList<>();

    @Value("${app.mcp.trust-proxy:false}")
    private boolean mcpTrustProxy;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                // v6.6: H2 Console/iframe 预览场景需要 sameOrigin frame 放行
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // v6.1fix: 只对 REQUEST 分发做鉴权，跳过 SSE 结束时的 async re-dispatch。
                        // 默认 shouldFilterAllDispatcherTypes=true 会让 AuthorizationFilter 在异步续派上重新鉴权，
                        // 而 JwtAuthFilter(OncePerRequestFilter) 不会重跑，安全上下文为空导致无谓的 Access Denied ERROR。
                        .shouldFilterAllDispatcherTypes(false)
                        .requestMatchers("/api/auth/login", "/api/auth/register", "/api/health",
                                "/actuator/health", "/actuator/prometheus", "/swagger-ui/**",
                                "/v3/api-docs/**", "/h2-console/**", "/error").permitAll()
                        .requestMatchers("/api/mcp/**").permitAll()
                        .requestMatchers("/api/settings/**", "/api/stats/**", "/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(HttpStatus.UNAUTHORIZED.value());
                    res.setContentType("application/json;charset=UTF-8");
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("code", 401);
                    body.put("message", "未登录或登录已过期");
                    body.put("data", null);
                    res.getWriter().write(objectMapper.writeValueAsString(
                            body));
                }))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // v8.9.1(12.4): MCP 来源白名单过滤器置于 JwtAuthFilter 之前（纵深第一道，
                // 控制器层保留第二道）；非 Bean 声明避免 Servlet 自动注册双重执行
                .addFilterBefore(new McpSourceFilter(objectMapper, mcpAllowedRemoteAddrs, mcpTrustProxy),
                        JwtAuthFilter.class);
        return http.build();
    }
}
