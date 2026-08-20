package com.testagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * vP1: 生产安全门禁。prod profile 下强制要求自定义 JWT Secret 与管理员密码，
 * 防止使用默认值上线。APP_ENFORCE_SECURITY=false 时仅告警不阻断启动。
 */
@Profile("prod")
@Component
public class ProductionGuard implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductionGuard.class);

    static final String DEFAULT_JWT_SECRET = "aicasetest-dev-secret-change-me-please-0123456789";
    static final String DEFAULT_ADMIN_PASSWORD = "admin123";
    static final String DEFAULT_MCP_BRIDGE_TOKEN = "aicasetest-mcp-local";
    private static final int MIN_JWT_SECRET_LENGTH = 32;
    private static final int MIN_ADMIN_PASSWORD_LENGTH = 12;

    @Value("${app.security.enforce:true}")
    private boolean enforce;

    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    @Value("${app.admin.password:admin123}")
    private String adminPassword;

    @Value("${app.mcp.bridge-token:aicasetest-mcp-local}")
    private String mcpBridgeToken;

    @Value("${llm.api-key:}")
    private String llmApiKey;

    @Override
    public void run(String... args) {
        List<String> violations = new ArrayList<>();
        String secret = jwtSecret == null ? "" : jwtSecret;
        if (secret.isBlank() || DEFAULT_JWT_SECRET.equals(secret) || secret.length() < MIN_JWT_SECRET_LENGTH) {
            violations.add("APP_JWT_SECRET 必须为自定义强密钥（至少 32 字符）");
        }
        String password = adminPassword == null ? "" : adminPassword;
        if (password.isBlank() || DEFAULT_ADMIN_PASSWORD.equals(password)
                || password.length() < MIN_ADMIN_PASSWORD_LENGTH) {
            violations.add("APP_ADMIN_PASSWORD 必须为自定义强密码（至少 12 字符）");
        }
        // v6.6: MCP 桥接接口须显式覆盖默认弱 token
        String mcpToken = mcpBridgeToken == null ? "" : mcpBridgeToken;
        if (mcpToken.isBlank() || DEFAULT_MCP_BRIDGE_TOKEN.equals(mcpToken)) {
            violations.add("MCP_BRIDGE_TOKEN 必须显式覆盖默认 token");
        }

        if (!violations.isEmpty()) {
            String message = String.join("；", violations);
            if (enforce) {
                throw new IllegalStateException("生产安全校验未通过: " + message);
            }
            log.error("生产安全配置存在风险（APP_ENFORCE_SECURITY=false，仅告警）: {}", message);
        }
        if (llmApiKey == null || llmApiKey.isBlank()) {
            log.warn("LLM_API_KEY 未配置，AI 生成与 Agent 执行能力将不可用");
        }
    }
}
