package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.dto.LlmCallResult;
import com.testagent.entity.TaskTelemetry;
import com.testagent.repository.TaskTelemetryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

// v5.14: 分析/生成/AI 评审的耗时与 token 埋点。
// 使用 ThreadLocal 栈，允许嵌套任务；LLM 调用会自动记到最内层任务的当前阶段。
@Component
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThreadLocal<Deque<TelemetryContext>> contextStack =
            ThreadLocal.withInitial(ArrayDeque::new);
    // v6.2: 并发子线程上的 phase 覆盖——前端/后端/组件池等子线程用它把 LLM token 记到正确 phase。
    private final ThreadLocal<String> localPhase = new ThreadLocal<>();

    @Autowired
    private TaskTelemetryRepository telemetryRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    public TelemetryContext start(String taskType, String projectId) {
        TelemetryContext ctx = new TelemetryContext(taskType, projectId, System.currentTimeMillis());
        contextStack.get().push(ctx);
        return ctx;
    }

    public boolean beginPhaseIfActive(String phase) {
        TelemetryContext ctx = current();
        if (ctx == null || phase == null || phase.isBlank()) {
            return false;
        }
        if (phase.equals(ctx.currentPhase)) {
            return true;
        }
        ctx.closePhase();
        ctx.currentPhase = phase;
        ctx.phaseStartedAt = System.currentTimeMillis();
        return true;
    }

    public void endPhase() {
        TelemetryContext ctx = current();
        if (ctx != null) {
            ctx.closePhase();
        }
    }

    public void recordLlmCall(LlmCallResult result) {
        TelemetryContext ctx = current();
        if (ctx == null || result == null) {
            return;
        }
        String phase = localPhase.get();
        if (phase == null) {
            phase = ctx.currentPhase == null ? "total" : ctx.currentPhase;
        }
        ctx.record(phase, result);
    }

    /**
     * v6.2: 返回当前线程的埋点上下文（供跨线程传播到子线程 bound 使用）。
     */
    public TelemetryContext currentContext() {
        return current();
    }

    /**
     * v6.2: 返回当前线程被 pin 住的 phase（子线程写入 LLM token 归属用）。
     */
    public String currentPhaseOverride() {
        return localPhase.get();
    }

    /**
     * v6.2: 在子线程上绑定共享上下文与 phase 后运行 task，使其中产生的 LLM token 也能归属到对应 phase。
     * ctx 为空时（如单元测试、无埋点上下文）原样执行，不丢功能。
     */
    public <T> T bindPhase(TelemetryContext ctx, String phase, Supplier<T> task) {
        if (task == null) {
            return null;
        }
        if (ctx == null) {
            return task.get();
        }
        Deque<TelemetryContext> stack = contextStack.get();
        String prevPhase = localPhase.get();
        stack.push(ctx);
        localPhase.set(phase);
        try {
            return task.get();
        } finally {
            localPhase.set(prevPhase);
            stack.pop();
        }
    }

    public boolean isActive() {
        return current() != null;
    }

    public void finish(boolean success) {
        Deque<TelemetryContext> stack = contextStack.get();
        TelemetryContext ctx = stack.pollFirst();
        if (ctx == null) {
            return;
        }
        ctx.closePhase();
        ctx.status = success ? "success" : "failed";
        persist(ctx);
        log.info("[Telemetry] task={} project={} status={} durationMs={} prompt={} completion={} total={}",
                ctx.taskType, ctx.projectId, ctx.status,
                System.currentTimeMillis() - ctx.startedAt,
                aggregate(ctx).promptTokens, aggregate(ctx).completionTokens, aggregate(ctx).totalTokens);
        // v6.6: 任务结束后清空本线程残留上下文与 phase，避免线程池复用时的脏状态/内存滞留
        stack.clear();
        localPhase.remove();
    }

    private TelemetryContext current() {
        return contextStack.get().peek();
    }

    private void persist(TelemetryContext ctx) {
        long totalDuration = System.currentTimeMillis() - ctx.startedAt;
        PhaseStat total = aggregate(ctx);
        ctx.phases.forEach((phase, stat) -> {
            saveRow(ctx, phase, stat.durationMs, stat);
            recordMetrics(ctx, phase, stat.durationMs, stat);
        });
        saveRow(ctx, "total", totalDuration, total);
        recordMetrics(ctx, "total", totalDuration, total);
    }

    private PhaseStat aggregate(TelemetryContext ctx) {
        PhaseStat total = new PhaseStat();
        for (PhaseStat stat : ctx.phases.values()) {
            total.durationMs += stat.durationMs;
            total.promptTokens += stat.promptTokens;
            total.completionTokens += stat.completionTokens;
            total.totalTokens += stat.totalTokens;
            total.firstTokenMs = Math.min(total.firstTokenMs, stat.firstTokenMs);
            total.calls += stat.calls;
        }
        return total;
    }

    private void saveRow(TelemetryContext ctx, String phase, long durationMs, PhaseStat stat) {
        try {
            TaskTelemetry row = new TaskTelemetry();
            row.setId(UUID.randomUUID().toString().substring(0, 12));
            row.setProjectId(ctx.projectId);
            row.setTaskType(ctx.taskType);
            row.setPhase(phase);
            row.setStatus(ctx.status);
            row.setDurationMs(durationMs);
            row.setFirstTokenMs(stat.firstTokenMs == Long.MAX_VALUE ? null : stat.firstTokenMs);
            row.setPromptTokens(stat.promptTokens);
            row.setCompletionTokens(stat.completionTokens);
            row.setTotalTokens(stat.totalTokens);
            row.setMetadata(objectMapper.writeValueAsString(Map.of("calls", stat.calls)));
            row.setCreatedAt(LocalDateTime.now());
            telemetryRepository.save(row);
        } catch (Exception e) {
            log.warn("[Telemetry] 保存失败 task={} phase={}: {}", ctx.taskType, phase, e.getMessage());
        }
    }

    private void recordMetrics(TelemetryContext ctx, String phase, long durationMs, PhaseStat stat) {
        try {
            String project = ctx.projectId == null ? "unknown" : ctx.projectId;
            Timer.builder("aicasetest.task.duration")
                    .tag("task", ctx.taskType)
                    .tag("phase", phase)
                    .tag("project", project)
                    .tag("status", ctx.status)
                    .register(meterRegistry)
                    .record(Duration.ofMillis(durationMs));

            Counter.builder("aicasetest.llm.tokens")
                    .tag("task", ctx.taskType)
                    .tag("phase", phase)
                    .tag("project", project)
                    .tag("token_type", "prompt")
                    .register(meterRegistry)
                    .increment(stat.promptTokens);
            Counter.builder("aicasetest.llm.tokens")
                    .tag("task", ctx.taskType)
                    .tag("phase", phase)
                    .tag("project", project)
                    .tag("token_type", "completion")
                    .register(meterRegistry)
                    .increment(stat.completionTokens);
            Counter.builder("aicasetest.llm.tokens")
                    .tag("task", ctx.taskType)
                    .tag("phase", phase)
                    .tag("project", project)
                    .tag("token_type", "total")
                    .register(meterRegistry)
                    .increment(stat.totalTokens);

            if (stat.firstTokenMs != Long.MAX_VALUE) {
                Timer.builder("aicasetest.llm.ttft")
                        .tag("task", ctx.taskType)
                        .tag("phase", phase)
                        .tag("project", project)
                        .register(meterRegistry)
                        .record(Duration.ofMillis(stat.firstTokenMs));
            }
            Counter.builder("aicasetest.llm.calls")
                    .tag("task", ctx.taskType)
                    .tag("phase", phase)
                    .tag("project", project)
                    .register(meterRegistry)
                    .increment(stat.calls);
        } catch (Exception e) {
            log.warn("[Telemetry] 指标记录失败 task={} phase={}: {}", ctx.taskType, phase, e.getMessage());
        }
    }

    public static class TelemetryContext {

        private final String taskType;
        private final String projectId;
        private final long startedAt;
        private String currentPhase = "total";
        private long phaseStartedAt = System.currentTimeMillis();
        private String status = "running";
        private final Map<String, PhaseStat> phases = new LinkedHashMap<>();
        private final Object lock = new Object();

        private TelemetryContext(String taskType, String projectId, long startedAt) {
            this.taskType = taskType;
            this.projectId = projectId;
            this.startedAt = startedAt;
            this.phaseStartedAt = startedAt;
        }

        private void closePhase() {
            synchronized (lock) {
                if (currentPhase == null) {
                    return;
                }
                PhaseStat stat = phases.computeIfAbsent(currentPhase, k -> new PhaseStat());
                stat.durationMs += System.currentTimeMillis() - phaseStartedAt;
                currentPhase = null;
            }
        }

        private void record(String phase, LlmCallResult result) {
            synchronized (lock) {
                String key = phase == null ? "total" : phase;
                PhaseStat stat = phases.computeIfAbsent(key, k -> new PhaseStat());
                stat.promptTokens += result.getPromptTokens() == null ? 0 : result.getPromptTokens();
                stat.completionTokens += result.getCompletionTokens() == null ? 0 : result.getCompletionTokens();
                stat.totalTokens += result.getTotalTokens() == null ? 0 : result.getTotalTokens();
                stat.calls++;
                if (result.getFirstTokenMs() != null) {
                    stat.firstTokenMs = Math.min(stat.firstTokenMs, result.getFirstTokenMs());
                }
            }
        }
    }

    private static class PhaseStat {

        private long durationMs;
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        private long firstTokenMs = Long.MAX_VALUE;
        private int calls;
    }
}
