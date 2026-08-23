package com.testagent.service;

import com.testagent.repository.TestCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v7.15(2a): 项目内展示序号分配器。
 *
 * 与 {@link TestCaseIdAllocator}（全局唯一 TC-xxx，防跨项目撞号静默覆盖）配合使用：
 * id 保证唯一性，project_seq 提供项目内从 1 连续的可读编号。
 *
 * 单实例部署前提下，per-project AtomicInteger 缓存 + 冷启动从 DB 加载 max(project_seq)；
 * 批量生成只查库一次，缓存随取号同步推进。删除用例产生的序号空洞不回收（与全局 id 同策略，
 * 保持历史可追溯）。
 */
@Component
public class ProjectSeqAllocator {

    private static final Logger log = LoggerFactory.getLogger(ProjectSeqAllocator.class);

    @Autowired
    private TestCaseRepository testCaseRepository;

    private final Map<String, AtomicInteger> caches = new ConcurrentHashMap<>();

    public synchronized int nextId(String projectId) {
        AtomicInteger cache = caches.get(projectId);
        if (cache == null) {
            int max = testCaseRepository.findMaxProjectSeq(projectId);
            log.info("[ProjectSeqAllocator] 项目 {} 冷启动加载最大序号: {}", projectId, max);
            cache = caches.computeIfAbsent(projectId, k -> new AtomicInteger(max));
        }
        return cache.incrementAndGet();
    }

    /**
     * v7.15(2a): 全量重生成前调用——replaceAll 会先删除项目全部用例，
     * 序号从 1 重新计数；丢弃旧缓存避免续用被删编号。
     */
    public synchronized void reset(String projectId) {
        caches.remove(projectId);
    }
}
