package com.testagent.common;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// v8.5: DNS rebinding 收敛——双解析一致性 + 全 A 记录内网判定（假 lookup 注入，无需真实 DNS）
class SafeDnsResolverTest {

    private static final String PUBLIC_1 = "93.184.216.34";
    private static final String PUBLIC_2 = "104.16.132.229";
    private static final String PRIVATE = "192.168.1.10";

    private static InetAddress addr(String ip) throws UnknownHostException {
        return InetAddress.getByName(ip);
    }

    private SafeDnsResolver resolverOf(Queue<List<String>> rounds) throws Exception {
        SafeDnsResolver resolver = new SafeDnsResolver(host -> {
            List<String> round = rounds.poll();
            if (round == null) {
                throw new UnknownHostException(host);
            }
            return round.stream().map(SafeDnsResolverTest::uncheckedAddr).toArray(InetAddress[]::new);
        });
        resolver.setProbeDelayMs(0);
        return resolver;
    }

    private static InetAddress uncheckedAddr(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rejectsInternalAddressInFirstRound() throws Exception {
        SafeDnsResolver resolver = resolverOf(new LinkedBlockingQueue<>(List.of(List.of(PRIVATE))));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.assertStablePublicHost("evil.example.com", "Git 地址"));
        assertTrue(ex.getMessage().contains("内网"));
    }

    @Test
    void rejectsRebindingRotationBetweenRounds() throws Exception {
        // v8.5: 首轮公网、次轮私网——经典 rebinding 序列必须被拦截
        SafeDnsResolver resolver = resolverOf(new LinkedBlockingQueue<>(
                List.of(List.of(PUBLIC_1), List.of(PRIVATE))));
        assertThrows(BusinessException.class,
                () -> resolver.assertStablePublicHost("evil.example.com", "URL"));
    }

    @Test
    void rejectsUnstablePublicRotation() throws Exception {
        // v8.5: 两轮均为公网但集合不同（TTL=0 轮换特征）同样拒绝
        SafeDnsResolver resolver = resolverOf(new LinkedBlockingQueue<>(
                List.of(List.of(PUBLIC_1), List.of(PUBLIC_2))));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.assertStablePublicHost("rotating.example.com", "URL"));
        assertTrue(ex.getMessage().contains("不一致"));
    }

    @Test
    void acceptsStablePublicHost() throws Exception {
        SafeDnsResolver resolver = resolverOf(new LinkedBlockingQueue<>(
                List.of(List.of(PUBLIC_1, PUBLIC_2), List.of(PUBLIC_2, PUBLIC_1))));
        assertDoesNotThrow(() -> resolver.assertStablePublicHost("stable.example.com", "Git 地址"));
    }

    @Test
    void unknownHostRejectedAsInvalidParam() throws Exception {
        SafeDnsResolver resolver = resolverOf(new LinkedBlockingQueue<>());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resolver.assertPublicHost("nonexistent.invalid", "URL"));
        assertTrue(ex.getMessage().contains("无法解析"));
    }
}
