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

// v5.14: 分析/生成/AI 评审的耗时与 token 埋点。
// 使用 ThreadLocal 栈，允许嵌套任务；LLM 调用会自动记到最内层任务的当前阶段。
@Component
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThreadLocal<Deque<TelemetryContext>> contextStack =
            ThreadLocal.withInitial(ArrayDeque::new);

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
        ctx.record(result);
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
            Timer.builder("aicasetest.task.duration")
                    .tag("task", ctx.taskType)
                    .tag("phase", phase)
                    .register(meterRegistry)
                    .record(Duration.ofMillis(durationMs));

            Counter.builder("aicasetest.llm.tokens")
                    .tag("task", ctx.taskType)
                    .tag("phase", phase)
                    .tag("token_type", "prompt")
                    .register(meterRegistry)
                    .increment(stat.promptTokens);
            Counter.builder("aicasetest.llm.tokens")
                    .tag("task", ctx.taskType)
                    .tag("phase", phase)
                    .tag("token_type", "completion")
                    .register(meterRegistry)
                    .increment(stat.completionTokens);
            Counter.builder("aicasetest.llm.tokens")
                    .tag("task", ctx.taskType)
                    .tag("phase", phase)
                    .tag("token_type", "total")
                    .register(meterRegistry)
                    .increment(stat.totalTokens);

            if (stat.firstTokenMs != Long.MAX_VALUE) {
                Timer.builder("aicasetest.llm.ttft")
                        .tag("task", ctx.taskType)
                        .tag("phase", phase)
                        .register(meterRegistry)
                        .record(Duration.ofMillis(stat.firstTokenMs));
            }
            Counter.builder("aicasetest.llm.calls")
                    .tag("task", ctx.taskType)
                    .tag("phase", phase)
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

        private TelemetryContext(String taskType, String projectId, long startedAt) {
            this.taskType = taskType;
            this.projectId = projectId;
            this.startedAt = startedAt;
            this.phaseStartedAt = startedAt;
        }

        private void closePhase() {
            if (currentPhase == null) {
                return;
            }
            PhaseStat stat = phases.computeIfAbsent(currentPhase, k -> new PhaseStat());
            stat.durationMs += System.currentTimeMillis() - phaseStartedAt;
            currentPhase = null;
        }

        private void record(LlmCallResult result) {
            String phase = currentPhase == null ? "total" : currentPhase;
            PhaseStat stat = phases.computeIfAbsent(phase, k -> new PhaseStat());
            stat.promptTokens += result.getPromptTokens() == null ? 0 : result.getPromptTokens();
            stat.completionTokens += result.getCompletionTokens() == null ? 0 : result.getCompletionTokens();
            stat.totalTokens += result.getTotalTokens() == null ? 0 : result.getTotalTokens();
            stat.calls++;
            if (result.getFirstTokenMs() != null) {
                stat.firstTokenMs = Math.min(stat.firstTokenMs, result.getFirstTokenMs());
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
