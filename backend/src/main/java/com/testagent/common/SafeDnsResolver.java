package com.testagent.common;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * v8.5: 公共 DNS 安全解析器——统一"全 A 记录内网判定 + 双解析一致性"校验，
 * 收敛 DNS rebinding TOCTOU 窗口（校验时解析安全 → 连接时 DNS 换记录指向内网）。
 *
 * 接入点：GitCloneService（http/https/git 克隆）、PrdAgent（URL 正文抓取）。
 *
 * 取舍说明（计划书 8.4 授权的轻量方案）：git 进程 / Jsoup 实际建连时仍由 OS 再解析一次，
 * JVM 内无法钉死 IP（彻底方案需 hosts 注入或代理，复杂度超预期）。双解析把攻击窗口从
 * "分钟级人工配置 TTL=0 记录"收窄到"秒级轮换恰好落在两次探测之间"，配合结果集一致性
 * 比对可拦截绝大多数 rebinding 序列；残留窗口在此记录，不再作为待办标注。
 */
@Component
public class SafeDnsResolver {

    // v8.5: 可注入的解析函数（getAllByName 声明受检异常，不能用标准 Function）
    @FunctionalInterface
    interface DnsLookup {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final DnsLookup lookup;

    // v8.5: 两轮解析间隔，给 TTL 轮换留暴露窗口；测试注入 0 免等待
    private long probeDelayMs = 300;

    public SafeDnsResolver() {
        this(InetAddress::getAllByName);
    }

    // 包级私有：单测注入假 lookup 模拟"首轮公网、次轮私网"等序列，无需真实 DNS
    SafeDnsResolver(DnsLookup lookup) {
        this.lookup = lookup;
    }

    // 包级私有：测试钉值用（探测间隔置 0）
    void setProbeDelayMs(long probeDelayMs) {
        this.probeDelayMs = probeDelayMs;
    }

    /**
     * 回环/私网/链路本地/通配地址判定（与 v8.4 GitCloneService/PrdAgent 既有口径一致）。
     */
    public boolean isInternal(InetAddress addr) {
        return addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                || addr.isLinkLocalAddress() || addr.isAnyLocalAddress();
    }

    /**
     * 解析全部 A 记录，任一命中内网段即抛 invalidParam。
     */
    public void assertPublicHost(String host, String desc) {
        assertAllPublic(resolveAll(host, desc), host, desc);
    }

    /**
     * 双解析一致性校验：两轮全部为公网地址，且两轮结果集一致。
     * 任一轮含内网地址、或两轮集合不同（rebinding 轮换特征）均拒绝。
     */
    public void assertStablePublicHost(String host, String desc) {
        List<InetAddress> first = resolveAll(host, desc);
        assertAllPublic(first, host, desc);
        sleepProbeInterval();
        List<InetAddress> second = resolveAll(host, desc);
        assertAllPublic(second, host, desc);
        if (!addressSet(first).equals(addressSet(second))) {
            throw BusinessException.invalidParam(
                    desc + "域名解析不稳定（两次结果不一致，疑似 DNS rebinding），禁止访问: " + host);
        }
    }

    private void assertAllPublic(List<InetAddress> addresses, String host, String desc) {
        for (InetAddress addr : addresses) {
            if (isInternal(addr)) {
                throw BusinessException.invalidParam(desc + "指向内网，禁止访问: " + host);
            }
        }
    }

    private void sleepProbeInterval() {
        if (probeDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(probeDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<InetAddress> resolveAll(String host, String desc) {
        try {
            return Arrays.asList(lookup.resolve(host));
        } catch (UnknownHostException e) {
            throw BusinessException.invalidParam(desc + "域名无法解析: " + host);
        }
    }

    private Set<String> addressSet(List<InetAddress> addresses) {
        Set<String> set = new TreeSet<>();
        for (InetAddress addr : addresses) {
            set.add(addr.getHostAddress());
        }
        return set;
    }
}
