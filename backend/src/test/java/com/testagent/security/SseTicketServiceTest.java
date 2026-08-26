package com.testagent.security;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v8.9.4(12.10): 票据双实现——Redis 模式（多实例互通，SETEX 带 TTL）与内存模式回落。
 */
class SseTicketServiceTest {

    @Test
    void redisModeStoresWithTtlAndAuthenticatesAcrossInstances() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(template.hasKey(anyString())).thenReturn(false);
        AtomicReference<String> storedKey = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            storedKey.set(inv.getArgument(0));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));

        SseTicketService writer = redisService(template);
        String ticket = writer.issue("alice", "ADMIN");

        assertEquals("sse:ticket:" + ticket, storedKey.get());
        verify(ops).set(eq(storedKey.get()), eq("alice|ADMIN"), any(Duration.class));

        // "另一个实例"用同一 Redis 读取票据
        when(ops.get(storedKey.get())).thenReturn("alice|ADMIN");
        SseTicketService reader = redisService(template);
        String[] principal = reader.authenticate(ticket);
        assertNotNull(principal);
        assertEquals("alice", principal[0]);
        assertEquals("ADMIN", principal[1]);
    }

    @Test
    void redisModeExpiredOrUnknownTicketReturnsNull() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(template.hasKey(anyString())).thenReturn(false);
        when(ops.get(anyString())).thenReturn(null);

        SseTicketService service = redisService(template);
        assertNull(service.authenticate("expired-or-unknown"));
    }

    @Test
    void memoryFallbackWorksWhenRedisDisabled() {
        SseTicketService service = new SseTicketService(300);
        // 未注入 redis 且 redisEnabled=false → 内存模式

        String ticket = service.issue("bob", "MEMBER");
        String[] principal = service.authenticate(ticket);

        assertNotNull(principal);
        assertEquals("bob", principal[0]);
    }

    private SseTicketService redisService(StringRedisTemplate template) {
        SseTicketService service = new SseTicketService(300);
        ReflectionTestUtils.setField(service, "redis", template);
        ReflectionTestUtils.setField(service, "redisEnabled", true);
        return service;
    }
}
