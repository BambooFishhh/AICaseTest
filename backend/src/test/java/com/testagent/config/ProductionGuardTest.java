package com.testagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionGuardTest {

    @Test
    void rejectsDefaultJwtSecretAndAdminPassword() {
        ProductionGuard guard = new ProductionGuard();
        ReflectionTestUtils.setField(guard, "enforce", true);
        ReflectionTestUtils.setField(guard, "jwtSecret", ProductionGuard.DEFAULT_JWT_SECRET);
        ReflectionTestUtils.setField(guard, "adminPassword", ProductionGuard.DEFAULT_ADMIN_PASSWORD);
        ReflectionTestUtils.setField(guard, "llmApiKey", "test-key");
        ReflectionTestUtils.setField(guard, "mcpBridgeToken", "custom-strong-mcp-token");

        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::run);
        assertTrue(ex.getMessage().contains("APP_JWT_SECRET"));
        assertTrue(ex.getMessage().contains("APP_ADMIN_PASSWORD"));
    }

    @Test
    void acceptsStrongSecrets() {
        ProductionGuard guard = new ProductionGuard();
        ReflectionTestUtils.setField(guard, "enforce", true);
        ReflectionTestUtils.setField(guard, "jwtSecret",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        ReflectionTestUtils.setField(guard, "adminPassword", "Strong-Admin-Pass-2026");
        ReflectionTestUtils.setField(guard, "llmApiKey", "test-key");
        ReflectionTestUtils.setField(guard, "mcpBridgeToken", "custom-strong-mcp-token-2026");

        guard.run();
    }
}
