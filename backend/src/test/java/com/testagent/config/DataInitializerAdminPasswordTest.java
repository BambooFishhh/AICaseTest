package com.testagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v8.9.4(12.10/N3): DataInitializer 弱回退默认清零——admin 密码空值确定性启动失败。
 */
class DataInitializerAdminPasswordTest {

    @Test
    void blankAdminPasswordFailsStartupDeterministically() throws Exception {
        DataInitializer initializer = new DataInitializer();
        ReflectionTestUtils.setField(initializer, "adminPassword", "  ");

        IllegalStateException ex = assertThrows(IllegalStateException.class, initializer::run);
        assertTrue(ex.getMessage().contains("APP_ADMIN_PASSWORD"));
    }
}
