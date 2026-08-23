package com.testagent.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.9(E7): 项目并发配额带超时获取验证。
 * 旧实现 Semaphore.acquire() 无限阻塞——项目排队可无限等待占满线程；
 * 新实现超时返回 false（调用方将执行记 failed），可用配额立即获取成功。
 */
class MemoryRuntimeStoreTryAcquireTest {

    @Test
    void timeoutReturnsFalseAfterWaiting() {
        MemoryRuntimeStore store = new MemoryRuntimeStore();
        store.acquireProjectPermit("p1", 1, "exec-1");   // 占满唯一配额
        long start = System.currentTimeMillis();
        boolean ok = store.tryAcquireProjectPermit("p1", 1, 120, "exec-2");
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(ok, "配额被占满时超时应返回 false（不再永久阻塞）");
        assertTrue(elapsed >= 100, "应真实等待至超时而非立即失败, elapsed=" + elapsed);
        store.releaseProjectPermit("p1", "exec-1");
    }

    @Test
    void availablePermitAcquiresImmediately() {
        MemoryRuntimeStore store = new MemoryRuntimeStore();
        assertTrue(store.tryAcquireProjectPermit("p1", 2, 100, "exec-1"));
        assertTrue(store.tryAcquireProjectPermit("p1", 2, 100, "exec-2"), "maxPermits=2 时第二个配额应立即可得");
        store.releaseProjectPermit("p1", "exec-1");
        store.releaseProjectPermit("p1", "exec-2");
    }

    @Test
    void releasedPermitCanBeReacquired() {
        MemoryRuntimeStore store = new MemoryRuntimeStore();
        store.acquireProjectPermit("p1", 1, "exec-1");
        store.releaseProjectPermit("p1", "exec-1");
        assertTrue(store.tryAcquireProjectPermit("p1", 1, 100, "exec-2"), "释放后配额可重新获取");
        store.releaseProjectPermit("p1", "exec-2");
    }

    @Test
    void acquireAndTryAcquireShareSamePermitPool() {
        MemoryRuntimeStore store = new MemoryRuntimeStore();
        store.acquireProjectPermit("p1", 1, "exec-1");
        assertFalse(store.tryAcquireProjectPermit("p1", 1, 50, "exec-2"), "acquire 占用的配额 tryAcquire 不可得（同一信号量池）");
        store.releaseProjectPermit("p1", "exec-1");
    }
}
