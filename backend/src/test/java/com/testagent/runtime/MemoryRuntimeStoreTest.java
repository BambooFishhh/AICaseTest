package com.testagent.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vT1: 内存运行态存储基线测试。
 */
class MemoryRuntimeStoreTest {

    private final MemoryRuntimeStore store = new MemoryRuntimeStore();

    @Test
    void flagSetAndClear() {
        assertFalse(store.isFlagSet("gen:cancel:p1"));
        store.setFlag("gen:cancel:p1", true);
        assertTrue(store.isFlagSet("gen:cancel:p1"));
        store.clearFlag("gen:cancel:p1");
        assertFalse(store.isFlagSet("gen:cancel:p1"));
    }

    @Test
    void sessionPutGetRemove() {
        assertNull(store.getSession("exec-1"));
        store.putSession("exec-1", "playwright-session");
        assertEquals("playwright-session", store.getSession("exec-1"));
        store.removeSession("exec-1");
        assertNull(store.getSession("exec-1"));
    }

    @Test
    void heartbeatPutGetRemove() {
        assertEquals(-1L, store.getHeartbeat("exec-1"));
        store.putHeartbeat("exec-1", 123456L);
        assertEquals(123456L, store.getHeartbeat("exec-1"));
        store.removeHeartbeat("exec-1");
        assertEquals(-1L, store.getHeartbeat("exec-1"));
    }

    @Test
    void loginAttemptsAndLockLifecycle() {
        assertEquals(1, store.incrementLoginAttempts("user"));
        assertEquals(1, store.getLoginAttempts("user"));
        store.setLockUntil("user", System.currentTimeMillis() + 60_000L);
        assertTrue(store.getLockUntil("user") > 0);
        store.clearLogin("user");
        assertEquals(0, store.getLoginAttempts("user"));
        assertTrue(store.getLockUntil("user") < 0);
    }

    @Test
    void projectPermitAcquireRelease() {
        store.acquireProjectPermit("p1", 3);
        store.acquireProjectPermit("p1", 3);
        store.acquireProjectPermit("p1", 3);
        store.releaseProjectPermit("p1");
        store.releaseProjectPermit("p1");
        store.releaseProjectPermit("p1");
        // 释放后可再次获取
        store.acquireProjectPermit("p1", 3);
        store.releaseProjectPermit("p1");
    }
}
