package com.testagent.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * v6.6: SSE 一次性/短期票据。浏览器 EventSource 无法携带 Authorization 头，
 * 传统做法把长期 JWT 拼到 URL query，存在泄露风险。改为：前端先走普通 JWT 换取
 * 一个短期、随机、可重用的 ticket，再用 ?ticket= 建立 SSE 连接。
 *
 * 票据默认 TTL 由 app.sse.ticket-ttl-seconds 控制（默认 300s），TTL 内可多次使用，
 * 以兼容 EventSource 断线重连共用同一 URL 的场景；过期后自动失效并被清除。
 *
 * v8.9.4(12.10): 双实现——APP_REDIS_ENABLED=true 时票据写 Redis（SETEX 带 TTL），
 * 多实例下 A 实例签发的票据在 B 实例可用（水平扩展前置）；未启用 Redis 时回落内存 Map
 * （仅单实例语义）。v8.9.4(12.10) 起该票据同时用于媒体路径（替代长期 JWT ?token=）。
 */
@Component
public class SseTicketService {

    private static final String REDIS_KEY_PREFIX = "sse:ticket:";

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentMap<String, Ticket> memoryTickets = new ConcurrentHashMap<>();
    private final long ttlMillis;

    private StringRedisTemplate redis;
    private boolean redisEnabled;

    public SseTicketService(@Value("${app.sse.ticket-ttl-seconds:300}") long ttlSeconds) {
        this.ttlMillis = Math.max(10, ttlSeconds) * 1000L;
    }

    @Autowired(required = false)
    void setRedis(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Value("${app.redis.enabled:false}")
    void setRedisEnabled(boolean redisEnabled) {
        this.redisEnabled = redisEnabled;
    }

    private boolean useRedis() {
        return redisEnabled && redis != null;
    }

    public String issue(String username, String role) {
        String ticket;
        do {
            ticket = Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong());
        } while (useRedis() && Boolean.TRUE.equals(redis.hasKey(REDIS_KEY_PREFIX + ticket))
                || memoryTickets.containsKey(ticket));
        if (useRedis()) {
            redis.opsForValue().set(REDIS_KEY_PREFIX + ticket,
                    username + "|" + role, Duration.ofMillis(ttlMillis));
        } else {
            memoryTickets.put(ticket, new Ticket(username, role, System.currentTimeMillis()));
            purgeIfNeeded();
        }
        return ticket;
    }

    /**
     * 校验票据，返回 {username, role}；无效/过期返回 null。
     */
    public String[] authenticate(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        if (useRedis()) {
            String value = redis.opsForValue().get(REDIS_KEY_PREFIX + ticket);
            if (value == null) {
                return null;
            }
            int sep = value.indexOf('|');
            if (sep < 0) {
                return null;
            }
            return new String[]{value.substring(0, sep), value.substring(sep + 1)};
        }
        Ticket t = memoryTickets.get(ticket);
        if (t == null) {
            return null;
        }
        if (System.currentTimeMillis() - t.createdAt > ttlMillis) {
            memoryTickets.remove(ticket);
            return null;
        }
        return new String[]{t.username, t.role};
    }

    private void purgeIfNeeded() {
        if (memoryTickets.size() < 1024) {
            return;
        }
        long now = System.currentTimeMillis();
        memoryTickets.entrySet().removeIf(e -> now - e.getValue().createdAt > ttlMillis);
    }

    private static final class Ticket {
        private final String username;
        private final String role;
        private final long createdAt;

        private Ticket(String username, String role, long createdAt) {
            this.username = username;
            this.role = role;
            this.createdAt = createdAt;
        }
    }
}
