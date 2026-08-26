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

    @Autowired(required = false)
    void setMeterRegistry(MeterRegistry registry) {
        this.registry = registry;
    }

    public void increment(String name, String... tagPairs) {
        if (registry == null) {
            return;
        }
        Counter.builder(name).tags(toTags(tagPairs)).register(registry).increment();
    }

    // v8.7.1: 启动期零值预注册——避免懒注册导致 /actuator/prometheus 首事件前面板断线
    public void registerCounter(String name, String... tagPairs) {
        if (registry == null) {
            return;
        }
        Counter.builder(name).tags(toTags(tagPairs)).register(registry);
    }

    public void registerTimer(String name, String... tagPairs) {
        if (registry == null) {
            return;
        }
        Timer.builder(name).tags(toTags(tagPairs)).register(registry);
    }

    public void incrementBy(String name, double amount, String... tagPairs) {
        if (registry == null) {
            return;
        }
        Counter.builder(name).tags(toTags(tagPairs)).register(registry).increment(amount);
    }

    public void recordMillis(String name, long millis, String... tagPairs) {
        if (registry == null) {
            return;
        }
        Timer.builder(name).tags(toTags(tagPairs)).register(registry)
                .record(Duration.ofMillis(millis));
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
