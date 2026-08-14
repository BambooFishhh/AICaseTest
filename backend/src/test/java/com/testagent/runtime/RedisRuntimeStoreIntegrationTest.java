package com.testagent.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vT7: Redis 运行态集成测试（真实 Redis 容器）。Docker 不可用时自动跳过。
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisRuntimeStoreIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofMinutes(3));

    private RedisRuntimeStore store;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();
        store = new RedisRuntimeStore(redisTemplate);
    }

    @Test
    void flagSetAndClear() {
        store.setFlag("gen:cancel:p1", true);
        assertTrue(store.isFlagSet("gen:cancel:p1"));
        store.clearFlag("gen:cancel:p1");
        assertFalse(store.isFlagSet("gen:cancel:p1"));
    }

    @Test
    void loginAttemptsAndLockPersistInRedis() {
        assertEquals(1, store.incrementLoginAttempts("user"));
        assertEquals(1, store.getLoginAttempts("user"));
        store.setLockUntil("user", System.currentTimeMillis() + 60_000L);
        assertTrue(store.getLockUntil("user") > 0);
        store.clearLogin("user");
        assertEquals(0, store.getLoginAttempts("user"));
        assertTrue(store.getLockUntil("user") < 0);
    }

    @Test
    void semaphoreAcquireRelease() {
        store.acquireProjectPermit("p1", 2);
        store.acquireProjectPermit("p1", 2);
        store.releaseProjectPermit("p1");
        store.releaseProjectPermit("p1");
        store.acquireProjectPermit("p1", 2);
        store.releaseProjectPermit("p1");
    }
}
