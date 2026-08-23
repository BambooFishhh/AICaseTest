package com.testagent.runtime;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v7.12(E15): Redis 信号量 ZSET 租约模型单测（无 Redis 依赖——内存语义 + 脚本内容校验）。
 * 旧实现缺陷：①计数器+TTL 键过期导致长执行超发；②释放无持有者语义，acquire 降级内存后
 * release 扣减 Redis（双向漂移偷槽位）。新实现：member=permitId 精确释放（幂等）、
 * 按授予来源路由、步骤心跳续租。
 */
class RedisRuntimeStorePermitTest {

    private StringRedisTemplate failingRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(ArgumentMatchers.<DefaultRedisScript<Long>>any(),
                ArgumentMatchers.anyList(), ArgumentMatchers.any(Object[].class)))
                .thenThrow(new RuntimeException("redis down"));
        return redis;
    }

    @Test
    void acquireFallbackRoutesReleaseToMemory() {
        // acquire 降级内存后登记 permitId，release 按授予来源还内存——
        // 旧实现 release 扣减 Redis（偷槽位）且内存信号量永久泄漏
        StringRedisTemplate redis = failingRedis();
        RedisRuntimeStore store = new RedisRuntimeStore(redis);

        store.acquireProjectPermit("p1", 1, "exec-1");   // 降级内存并登记
        store.releaseProjectPermit("p1", "exec-1");      // 路由回内存释放

        // 内存槽位已归还：下一个执行可立即获取（若释放被错误路由到 Redis，
        // 内存信号量仍满，tryAcquire 超时返回 false——测试失败而非挂死）
        assertTrue(store.tryAcquireProjectPermit("p1", 1, 200, "exec-2"),
                "内存授予的配额释放后应可重新获取（按来源路由）");
    }

    @Test
    void duplicateReleaseIsIdempotent() {
        StringRedisTemplate redis = failingRedis();
        RedisRuntimeStore store = new RedisRuntimeStore(redis);

        store.acquireProjectPermit("p1", 1, "exec-1");
        assertDoesNotThrow(() -> {
            store.releaseProjectPermit("p1", "exec-1");
            store.releaseProjectPermit("p1", "exec-1");   // 重复释放：ZREM 幂等 / 路由集已移除
            store.releaseProjectPermit("p1", "never-held"); // 释放未持有：无害 no-op
        }, "重复/未持有的释放必须幂等无害");
    }

    @Test
    void renewSkipsRedisForMemoryGrantedPermit() {
        StringRedisTemplate redis = failingRedis();
        RedisRuntimeStore store = new RedisRuntimeStore(redis);

        store.acquireProjectPermit("p1", 1, "exec-1");   // 降级内存登记
        reset(redis);                                    // 清除 acquire 期间的调用记录
        store.renewProjectPermit("p1", "exec-1");        // 内存授予：no-op

        verify(redis, never()).execute(ArgumentMatchers.<DefaultRedisScript<Long>>any(),
                ArgumentMatchers.anyList(), ArgumentMatchers.any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void leaseScriptsContainZsetSemantics() {
        // Lua 脚本静态内容校验：ZSET 租约模型关键语义必须在位
        DefaultRedisScript<Long> acquire = (DefaultRedisScript<Long>)
                ReflectionTestUtils.getField(RedisRuntimeStore.class, "ACQUIRE_SCRIPT");
        DefaultRedisScript<Long> release = (DefaultRedisScript<Long>)
                ReflectionTestUtils.getField(RedisRuntimeStore.class, "RELEASE_SCRIPT");
        DefaultRedisScript<Long> renew = (DefaultRedisScript<Long>)
                ReflectionTestUtils.getField(RedisRuntimeStore.class, "RENEW_SCRIPT");

        String acq = acquire.getScriptAsString();
        assertTrue(acq.contains("ZREMRANGEBYSCORE"), "acquire 必须先清理过期租约");
        assertTrue(acq.contains("ZCARD"), "acquire 必须检查持有数上限");
        assertTrue(acq.contains("ZADD"), "acquire 必须登记 permitId 租约");
        assertTrue(acq.contains("PEXPIRE"), "acquire 必须续 key TTL 防全量丢失");

        assertTrue(release.getScriptAsString().contains("ZREM"), "release 必须按 member 精确移除");

        String ren = renew.getScriptAsString();
        assertTrue(ren.contains("ZSCORE"), "renew 必须校验持有者存在");
        assertTrue(ren.contains("ZADD"), "renew 必须刷新租约 score");
    }

    @Test
    void renewCallsRedisForRedisGrantedPermit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(ArgumentMatchers.<DefaultRedisScript<Long>>any(),
                ArgumentMatchers.anyList(), ArgumentMatchers.any(Object[].class)))
                .thenReturn(1L);   // Redis 可用：acquire 走 Redis 成功
        RedisRuntimeStore store = new RedisRuntimeStore(redis);

        store.acquireProjectPermit("p1", 1, "exec-r");
        reset(redis);
        store.renewProjectPermit("p1", "exec-r");        // Redis 授予：走续租脚本

        verify(redis).execute(ArgumentMatchers.<DefaultRedisScript<Long>>any(),
                ArgumentMatchers.anyList(), ArgumentMatchers.any(Object[].class));
    }
}
