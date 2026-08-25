package com.testagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// v8.5: 弱默认密钥清零——任一关键钥缺失启动必须失败且指明环境变量名
class SecurityKeyGuardTest {

    private SecurityKeyGuard guardWith(String jwt, String admin, String milvus, String mcp) {
        SecurityKeyGuard guard = new SecurityKeyGuard();
        ReflectionTestUtils.setField(guard, "jwtSecret", jwt);
        ReflectionTestUtils.setField(guard, "adminPassword", admin);
        ReflectionTestUtils.setField(guard, "milvusPassword", milvus);
        ReflectionTestUtils.setField(guard, "mcpBridgeToken", mcp);
        return guard;
    }

    @Test
    void passesWhenAllKeysPresent() {
        SecurityKeyGuard guard = guardWith(
                "0123456789abcdef0123456789abcdef",
                "some-admin-password",
                "some-milvus-password",
                "some-mcp-token");
        assertDoesNotThrow(guard::afterPropertiesSet);
    }

    @Test
    void failsOnMissingJwtSecretAndNamesEnvVar() {
        SecurityKeyGuard guard = guardWith("", "admin-pw", "milvus-pw", "mcp-token");
        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("APP_JWT_SECRET"));
        assertTrue(ex.getMessage().contains(".env"));
    }

    @Test
    void failsOnBlankMilvusPassword() {
        SecurityKeyGuard guard = guardWith("jwt-secret", "admin-pw", "  ", "mcp-token");
        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("MILVUS_PASSWORD"));
    }

    @Test
    void failsOnMissingMcpBridgeToken() {
        SecurityKeyGuard guard = guardWith("jwt-secret", "admin-pw", "milvus-pw", "");
        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("MCP_BRIDGE_TOKEN"));
    }

    @Test
    void listsAllMissingKeysAtOnce() {
        SecurityKeyGuard guard = guardWith(null, "", null, null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
        // v8.5: 一次列出全部缺失项，避免逐个补键反复重启
        assertTrue(ex.getMessage().contains("APP_JWT_SECRET"));
        assertTrue(ex.getMessage().contains("APP_ADMIN_PASSWORD"));
        assertTrue(ex.getMessage().contains("MILVUS_PASSWORD"));
        assertTrue(ex.getMessage().contains("MCP_BRIDGE_TOKEN"));
    }
}
