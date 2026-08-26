package com.testagent.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v8.7.1(9.5.1): 统一指标入口——各服务不直接持有 MeterRegistry。
 * registry 未注入（直 new 单测/极简环境）时全部方法 no-op，调用方零判空。
 * 命名约定：前缀 gen_/milvus_/executor_/llm_/rag_；Counter 以 _total 结尾。
 */
@Component
public class MetricsFacade {

    private MeterRegistry registry;
    // v8.7.1: Gauge 强引用表——micrometer gauge 只持弱引用，无强引用会被 GC 归零
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    // v8.9.2(12.6/C7): 热路径 meter 缓存——避免每次 increment 都走 Builder+registry 查找
    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final Map<String, Timer> timerCache = new ConcurrentHashMap<>();

    @Autowired(required = false)
    void setMeterRegistry(MeterRegistry registry) {
        this.registry = registry;
    }

    public void increment(String name, String... tagPairs) {
        if (registry == null) {
            return;
        }
        counterFor(name, tagPairs).increment();
    }

    // v8.7.1: 启动期零值预注册——避免懒注册导致 /actuator/prometheus 首事件前面板断线
    public void registerCounter(String name, String... tagPairs) {
        if (registry == null) {
            return;
        }
        counterFor(name, tagPairs);
    }

    private Counter counterFor(String name, String... tagPairs) {
        List<Tag> tags = toTags(tagPairs);
        String key = name + tags;
        return counterCache.computeIfAbsent(key,
                k -> Counter.builder(name).tags(tags).register(registry));
    }

    public void registerTimer(String name, String... tagPairs) {
        if (registry == null) {
            return;
        }
        timerFor(name, tagPairs);
    }

    private Timer timerFor(String name, String... tagPairs) {
        List<Tag> tags = toTags(tagPairs);
        String key = name + tags;
        return timerCache.computeIfAbsent(key,
                k -> Timer.builder(name).tags(tags).register(registry));
    }

    public void incrementBy(String name, double amount, String... tagPairs) {
        if (registry == null) {
            return;
        }
        counterFor(name, tagPairs).increment(amount);
    }

    public void recordMillis(String name, long millis, String... tagPairs) {
        if (registry == null) {
            return;
        }
        timerFor(name, tagPairs).record(Duration.ofMillis(millis));
    }

    /**
     * 设置 Gauge 值：内部以 AtomicLong 为强引用载体，重复调用只更新值不重复注册。
     */
    public void setGauge(String name, long value, String... tagPairs) {
        List<Tag> tags = toTags(tagPairs);
        String key = name + tags;
        AtomicLong ref = gauges.computeIfAbsent(key, k -> {
            AtomicLong created = new AtomicLong(value);
            if (registry != null) {
                registry.gauge(name, tags, created);
            }
            return created;
        });
        ref.set(value);
    }

    // v8.8.2(10.5): 调用方自持强引用的原始 Gauge 注册（如 TaskBacklogMetrics 的状态计数表）
    public void gaugeRaw(String name, Number holder) {
        if (registry == null || holder == null) {
            return;
        }
        registry.gauge(name, holder);
    }

    private List<Tag> toTags(String... tagPairs) {
        List<Tag> tags = new ArrayList<>();
        if (tagPairs != null) {
            for (int i = 0; i + 1 < tagPairs.length; i += 2) {
                tags.add(Tag.of(tagPairs[i], tagPairs[i + 1] == null ? "unknown" : tagPairs[i + 1]));
            }
        }
        return tags;
    }
}
