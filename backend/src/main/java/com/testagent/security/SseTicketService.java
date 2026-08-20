package com.testagent.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * v6.6: SSE 一次性/短期票据。浏览器 EventSource 无法携带 Authorization 头，
 * 传统做法把长期 JWT 拼到 URL query，存在泄露风险。改为：前端先走普通 JWT 换取
 * 一个短期、随机、可重用的 ticket，再用 ?ticket= 建立 SSE 连接。
 *
 * 票据默认 TTL 由 app.sse.ticket-ttl-seconds 控制（默认 300s），TTL 内可多次使用，
 * 以兼容 EventSource 断线重连共用同一 URL 的场景；过期后自动失效并被清除。
 */
@Component
public class SseTicketService {

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentMap<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public SseTicketService(@Value("${app.sse.ticket-ttl-seconds:300}") long ttlSeconds) {
        this.ttlMillis = Math.max(10, ttlSeconds) * 1000L;
    }

    public String issue(String username, String role) {
        String ticket;
        do {
            ticket = Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong());
        } while (tickets.containsKey(ticket));
        tickets.put(ticket, new Ticket(username, role, System.currentTimeMillis()));
        purgeIfNeeded();
        return ticket;
    }

    /**
     * 校验票据，返回 {username, role}；无效/过期返回 null。
     */
    public String[] authenticate(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        Ticket t = tickets.get(ticket);
        if (t == null) {
            return null;
        }
        if (System.currentTimeMillis() - t.createdAt > ttlMillis) {
            tickets.remove(ticket);
            return null;
        }
        return new String[]{t.username, t.role};
    }

    private void purgeIfNeeded() {
        if (tickets.size() < 1024) {
            return;
        }
        long now = System.currentTimeMillis();
        tickets.entrySet().removeIf(e -> now - e.getValue().createdAt > ttlMillis);
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
