package com.testagent.service;

import com.testagent.entity.TestCase;
import com.testagent.repository.TestCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * v7.11(T1/T2): 用例 ID 全局唯一分配器。
 *
 * 背景：test_cases 表 id 是全局主键（无 projectId 复合），而历史编号处处按项目独立分配
 * （生成从 TC-001 重编、追加/导入取项目内 max+1、手动创建用 size()+1）——JPA 对非 null id
 * 的 save 走 merge，跨项目/删号复用的同号用例会静默整行覆盖他行（projectId 一并改写）。
 *
 * 方案：全部新建路径统一走本分配器，取全库 TC- 前缀最大数字后缀 +1。
 * - JVM 内 synchronized + AtomicInteger 缓存：进程内取号互斥且批量生成只查库一次；
 * - 单实例部署前提下无跨进程竞争（多实例为已知限制，后续如需可换 DB 序列表）。
 */
@Component
public class TestCaseIdAllocator {

    private static final Logger log = LoggerFactory.getLogger(TestCaseIdAllocator.class);

    @Autowired
    private TestCaseRepository testCaseRepository;

    // -1 表示未从 DB 初始化；首次取号时加载全库 max
    private final AtomicInteger cachedMax = new AtomicInteger(-1);

    public synchronized String nextId() {
        if (cachedMax.get() < 0) {
            cachedMax.set(loadMaxFromDb());
            log.info("[TestCaseIdAllocator] 冷启动加载全库用例最大编号: {}", cachedMax.get());
        }
        int next = cachedMax.incrementAndGet();
        return String.format("TC-%03d", next);
    }

    /** 供单测重置缓存 */
    synchronized void resetCache() {
        cachedMax.set(-1);
    }

    private int loadMaxFromDb() {
        int max = 0;
        for (TestCase tc : testCaseRepository.findAll()) {
            int n = parseSuffix(tc.getId());
            if (n > max) {
                max = n;
            }
        }
        return max;
    }

    static int parseSuffix(String id) {
        if (id == null || !id.startsWith("TC-")) {
            return 0;
        }
        try {
            return Integer.parseInt(id.substring(3));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
