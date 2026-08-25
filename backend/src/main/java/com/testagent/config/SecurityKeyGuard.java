package com.testagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * v8.5: 弱默认密钥清零——所有 profile 下关键钥缺失即启动失败，消灭"默认值即漏洞"。
 * 只校验"非空"（必填），强度检查（长度/默认值黑名单）仍归 prod profile 的 ProductionGuard，
 * 两层门禁不合并：本地开发允许弱值，生产强制强值。
 *
 * 实现说明：用 InitializingBean（刷新期）而非 EnvironmentPostProcessor——
 * spring-dotenv 同样以 EnvironmentPostProcessor 注册属性源，两者顺序无契约保证，
 * 若先于 dotenv 执行会把本地 .env 已配置的键误判缺失；InitializingBean 在全部
 * 属性源就绪后的容器刷新期执行，时序确定且早于 ProductionGuard 的启动完成期检查。
 */
@Component
public class SecurityKeyGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SecurityKeyGuard.class);

    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Value("${app.milvus.password:}")
    private String milvusPassword;

    @Value("${app.mcp.bridge-token:}")
    private String mcpBridgeToken;

    @Override
    public void afterPropertiesSet() {
        // v8.5: 键名与 application.yml 占位符一一对应，缺失时逐项指明环境变量名
        List<String> missing = new ArrayList<>();
        if (isBlank(jwtSecret)) {
            missing.add("APP_JWT_SECRET");
        }
        if (isBlank(adminPassword)) {
            missing.add("APP_ADMIN_PASSWORD");
        }
        if (isBlank(milvusPassword)) {
            missing.add("MILVUS_PASSWORD");
        }
        if (isBlank(mcpBridgeToken)) {
            missing.add("MCP_BRIDGE_TOKEN");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("安全密钥缺失，拒绝启动。请在根目录 .env 或环境中配置: "
                    + String.join(", ", missing)
                    + "（参照 .env.example；任何 profile 下均必填）");
        }
        log.info("SecurityKeyGuard: 安全密钥已配置 ({} 项)", 4);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
