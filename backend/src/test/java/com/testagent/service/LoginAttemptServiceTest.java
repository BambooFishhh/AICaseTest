package com.testagent.service;

import com.testagent.common.BusinessException;
import com.testagent.runtime.MemoryRuntimeStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * vT1: 登录防爆破基线测试。
 */
class LoginAttemptServiceTest {

    private LoginAttemptService newService() {
        LoginAttemptService service = new LoginAttemptService();
        ReflectionTestUtils.setField(service, "runtimeStore", new MemoryRuntimeStore());
        return service;
    }

    @Test
    void lockAfterFiveFailures() {
        LoginAttemptService service = newService();
        for (int i = 0; i < 5; i++) {
            service.loginFailed("user");
        }
        BusinessException ex = assertThrows(BusinessException.class, () -> service.checkLocked("user"));
        assertEquals(40104, ex.getCode());
    }

    @Test
    void noLockBelowThreshold() {
        LoginAttemptService service = newService();
        for (int i = 0; i < 4; i++) {
            service.loginFailed("user");
        }
        assertDoesNotThrow(() -> service.checkLocked("user"));
    }

    @Test
    void successClearsLockState() {
        LoginAttemptService service = newService();
        service.loginFailed("user");
        service.loginSucceeded("user");
        assertDoesNotThrow(() -> service.checkLocked("user"));
    }
}
