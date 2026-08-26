package com.testagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * v8.9.1(12.4): MCP 桥接来源过滤器——来源白名单校验从控制器层提到过滤器层，
 * 在 JwtAuthFilter 之前拦截（permitAll 路径的纵深第一道）。
 *
 * 反代适配：app.mcp.trust-proxy=true 时取 X-Forwarded-For 首跳判定客户端真实 IP。
 * ⚠️ 仅能在可信反代之后开启，否则该头可被客户端伪造绕过白名单。
 *
 * 非Spring Bean 声明（由 SecurityConfig 构造注册进安全链），
 * 避免 Boot 对 OncePerRequestFilter Bean 的 Servlet 容器自动注册造成双重执行。
 */
public class McpSourceFilter extends OncePerRequestFilter {

    public static final String MCP_PATH_PREFIX = "/api/mcp/";

    private final ObjectMapper objectMapper;
    private final Set<String> allowedRemoteAddrs;
    private final boolean trustProxy;

    public McpSourceFilter(ObjectMapper objectMapper, List<String> allowedRemoteAddrs, boolean trustProxy) {
        this.objectMapper = objectMapper;
        this.allowedRemoteAddrs = allowedRemoteAddrs == null ? Set.of() : Set.copyOf(allowedRemoteAddrs);
        this.trustProxy = trustProxy;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(MCP_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ip = resolveClientIp(request, trustProxy);
        if (isLoopbackIp(ip) || allowedRemoteAddrs.contains(ip)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(40300, "MCP bridge 仅允许本机回环访问")));
    }

    /**
     * 客户端 IP 解析（v8.9.1 抽公共静态方法：过滤器与控制器第二道防线同口径）。
     */
    public static String resolveClientIp(HttpServletRequest request, boolean trustProxy) {
        if (trustProxy) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // 首跳 = 最初发起请求的客户端（后续为代理链）
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    public static boolean isLoopbackIp(String ip) {
        return ip != null && (ip.equals("127.0.0.1") || ip.startsWith("127.")
                || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1"));
    }
}
