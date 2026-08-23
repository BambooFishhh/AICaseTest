package com.testagent.service;

import com.testagent.entity.AgentTask;
import com.testagent.entity.AgentTaskEvent;
import com.testagent.repository.AgentTaskEventRepository;
import com.testagent.repository.AgentTaskRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * v6.5: agent_task 生命周期管理。业务异步线程负责 create/start/checkpoint/收尾，
 * 恢复逻辑只处理 lease 过期的 RUNNING 任务并标记 NEEDS_REVIEW，不自动重跑。
 */
@Service
public class AgentTaskService {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskService.class);

    public static final String TYPE_ANALYSIS = "analysis";
    public static final String TYPE_GENERATION = "generation";
    public static final String TYPE_APPEND_GENERATION = "append_generation";
    public static final String TYPE_EXECUTION = "execution";

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_NEEDS_REVIEW = "NEEDS_REVIEW";
    public static final String STATUS_DLQ = "DLQ";

    // v7.11(E14): 终态集合——收尾类操作（succeed/fail/cancel 等）遇终态跳过，
    // 防止 CANCELLED 被排队超时 fail()/迟到 worker succeed() 覆盖
    private static final java.util.Set<String> TERMINAL_STATUSES =
            java.util.Set.of(STATUS_SUCCEEDED, STATUS_FAILED, STATUS_CANCELLED,
                    STATUS_NEEDS_REVIEW, STATUS_DLQ);

    @Autowired
    private AgentTaskRepository agentTaskRepository;

    @Value("${app.ha.task-lease-seconds:600}")
    private int taskLeaseSeconds = 600;

    @Value("${app.ha.task-ttl-minutes:60}")
    private int taskTtlMinutes = 60;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Autowired(required = false)
    private TaskEventStreamService taskEventStreamService;

    @Autowired
    private AgentTaskEventRepository agentTaskEventRepository;

    public String createTask(String taskType, String projectId, String requestId, String inputJson) {
        AgentTask latest = agentTaskRepository
                .findFirstByRequestIdAndTaskTypeOrderByCreatedAtDesc(requestId, taskType)
                .orElse(null);
        if (latest != null && isActive(latest.getStatus())) {
            log.debug("Reuse active agent task {} for {}:{}", latest.getId(), taskType, requestId);
            return latest.getId();
        }

        AgentTask task = new AgentTask();
        task.setId(UUID.randomUUID().toString());
        task.setRequestId(requestId);
        task.setTaskType(taskType);
        task.setProjectId(projectId);
        task.setStatus(STATUS_QUEUED);
        task.setPhase("created");
        task.setAttempts(0);
        task.setMaxAttempts(3);
        task.setInputJson(inputJson);
        task.setDegraded(false);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        String savedId = agentTaskRepository.save(task).getId();
        if (taskEventStreamService != null) {
            taskEventStreamService.publish(savedId);
        }
        return savedId;
    }

    /**
     * v6.6: 执行任务使用固定 taskId（= executionId），便于执行链路直接按 executionId 收尾。
     */
    public String createTaskWithId(String id, String taskType, String projectId,
                                   String requestId, String inputJson) {
        AgentTask task = new AgentTask();
        task.setId(id);
        task.setRequestId(requestId);
        task.setTaskType(taskType);
        task.setProjectId(projectId);
        task.setStatus(STATUS_QUEUED);
        task.setPhase("created");
        task.setAttempts(0);
        task.setMaxAttempts(3);
        task.setInputJson(inputJson);
        task.setDegraded(false);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        String savedId = agentTaskRepository.save(task).getId();
        if (taskEventStreamService != null) {
            taskEventStreamService.publish(savedId);
        }
        return savedId;
    }

    /**
     * v6.8: CAS 抢占 QUEUED 任务。成功返回 true，被其他 worker 抢占则返回 false。
     */
    public boolean claimQueued(String taskId) {
        LocalDateTime now = LocalDateTime.now();
        int updated = agentTaskRepository.claimQueued(taskId, STATUS_RUNNING, "queued_claimed",
                UUID.randomUUID().toString(), now.plusSeconds(taskLeaseSeconds), now, now);
        if (updated > 0) {
            metric("aicasetest.task.started");
        }
        return updated > 0;
    }

    public void start(String taskId) {
        update(taskId, task -> {
            task.setStatus(STATUS_RUNNING);
            task.setPhase("started");
            task.setAttempts((task.getAttempts() == null ? 0 : task.getAttempts()) + 1);
            task.setErrorCode(null);
            task.setErrorMessage(null);
            task.setLeaseOwner(UUID.randomUUID().toString());
            task.setLeaseExpireAt(LocalDateTime.now().plusSeconds(taskLeaseSeconds));
            task.setHeartbeatAt(LocalDateTime.now());
            task.setStartedAt(LocalDateTime.now());
            task.setEndedAt(null);
        });
        metric("aicasetest.task.started");
    }

    public void checkpoint(String taskId, String phase, String checkpointJson) {
        update(taskId, task -> {
            task.setPhase(phase);
            task.setCheckpointJson(checkpointJson);
            task.setHeartbeatAt(LocalDateTime.now());
            task.setLeaseExpireAt(LocalDateTime.now().plusSeconds(taskLeaseSeconds));
        });
    }

    public void succeed(String taskId) {
        update(taskId, task -> {
            if (skipIfTerminal(task, STATUS_SUCCEEDED)) {
                return;
            }
            task.setStatus(STATUS_SUCCEEDED);
            task.setPhase("completed");
            task.setEndedAt(LocalDateTime.now());
            task.setLeaseOwner(null);
            task.setLeaseExpireAt(null);
        });
        metric("aicasetest.task.completed");
    }

    public void fail(String taskId, String errorCode, String errorMessage) {
        update(taskId, task -> {
            if (skipIfTerminal(task, STATUS_FAILED)) {
                return;
            }
            finishFailure(task, STATUS_FAILED, errorCode, errorMessage);
        });
        metric("aicasetest.task.failed");
    }

    public void cancel(String taskId) {
        update(taskId, task -> {
            if (skipIfTerminal(task, STATUS_CANCELLED)) {
                return;
            }
            finishFailure(task, STATUS_CANCELLED, "USER_CANCELLED",
                    "用户取消任务");
        });
    }

    public void markNeedsReview(String taskId, String errorCode, String errorMessage) {
        update(taskId, task -> {
            if (skipIfTerminal(task, STATUS_NEEDS_REVIEW)) {
                return;
            }
            finishFailure(task, STATUS_NEEDS_REVIEW, errorCode, errorMessage);
        });
    }

    /**
     * v6.7: 任务降级标记（如规则兜底生成），保留结果但提示质量下降。
     */
    public void markDegraded(String taskId) {
        update(taskId, task -> task.setDegraded(true));
        metric("aicasetest.task.degraded_total");
    }

    public Integer getAttempt(String taskId) {
        AgentTask task = findById(taskId);
        return task == null || task.getAttempts() == null ? 0 : task.getAttempts();
    }

    public void markDlq(String taskId, String errorCode, String errorMessage) {
        update(taskId, task -> {
            if (skipIfTerminal(task, STATUS_DLQ)) {
                return;
            }
            finishFailure(task, STATUS_DLQ, errorCode, errorMessage);
        });
        metric("aicasetest.task.dlq_total");
    }

    /**
     * v7.11(E14): 任务已处终态时跳过翻转（幂等目标态除外），返回 true 表示跳过。
     * requeue（管理员显式重试）不受此保护。
     */
    private boolean skipIfTerminal(AgentTask task, String targetStatus) {
        String current = task.getStatus();
        if (current != null && TERMINAL_STATUSES.contains(current) && !current.equals(targetStatus)) {
            log.warn("Agent task {} 已是终态 {}，跳过翻转为 {}", task.getId(), current, targetStatus);
            return true;
        }
        return false;
    }

    public void requeue(String taskId) {
        update(taskId, task -> {
            task.setStatus(STATUS_QUEUED);
            task.setPhase("queued");
            task.setErrorCode(null);
            task.setErrorMessage(null);
            task.setEndedAt(null);
            task.setLeaseOwner(null);
            task.setLeaseExpireAt(null);
        });
    }

    public AgentTask findById(String taskId) {
        return agentTaskRepository.findById(taskId).orElse(null);
    }

    public Page<AgentTask> list(String taskType, String status, String projectId, int page, int size) {
        Specification<AgentTask> spec = Specification.where(null);
        if (taskType != null && !taskType.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("taskType"), taskType));
        }
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (projectId != null && !projectId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("projectId"), projectId));
        }
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return agentTaskRepository.findAll(spec, pageable);
    }

    public List<AgentTask> findQueued() {
        return agentTaskRepository.findTop20ByStatusOrderByCreatedAtAsc(STATUS_QUEUED);
    }

    public List<AgentTaskEvent> timeline(String taskId) {
        return agentTaskEventRepository.findByTaskIdOrderByCreatedAtAsc(taskId);
    }

    public Map<String, Long> statusCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : agentTaskRepository.countGroupByStatus()) {
            counts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return counts;
    }

    /**
     * 启动恢复 + 定时恢复：lease 过期的 RUNNING 任务标记 NEEDS_REVIEW，由管理员确认后重试。
     */
    public int recoverStaleTasks() {
        LocalDateTime cutoff = LocalDateTime.now();
        List<AgentTask> stale = agentTaskRepository
                .findByStatusAndLeaseExpireAtBefore(STATUS_RUNNING, cutoff);
        for (AgentTask task : stale) {
            task.setStatus(STATUS_NEEDS_REVIEW);
            task.setErrorCode("LEASE_EXPIRED");
            task.setErrorMessage("服务重启或租约过期导致任务中断，请在任务中心确认后重试");
            task.setEndedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            agentTaskRepository.save(task);
            recordEvent(task);
        }
        if (!stale.isEmpty()) {
            log.info("v6.5: recovered {} stale agent tasks -> NEEDS_REVIEW", stale.size());
            metric("aicasetest.task.lease_expired_total",
                    "count", String.valueOf(stale.size()));
        }
        return stale.size();
    }

    /**
     * v6.6: 运行超过 TTL 的任务标记 NEEDS_REVIEW，避免"活着的僵尸任务"永久占用配额。
     */
    public int expireTasksByTtl() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(taskTtlMinutes);
        List<AgentTask> stale = agentTaskRepository
                .findByStatusAndStartedAtBefore(STATUS_RUNNING, cutoff);
        for (AgentTask task : stale) {
            task.setStatus(STATUS_NEEDS_REVIEW);
            task.setErrorCode("TTL_EXCEEDED");
            task.setErrorMessage("任务运行超过 " + taskTtlMinutes + " 分钟，已转入人工复核");
            task.setEndedAt(LocalDateTime.now());
            task.setLeaseOwner(null);
            task.setLeaseExpireAt(null);
            task.setUpdatedAt(LocalDateTime.now());
            agentTaskRepository.save(task);
            recordEvent(task);
        }
        if (!stale.isEmpty()) {
            log.info("v6.6: expired {} agent tasks by TTL({}m) -> NEEDS_REVIEW", stale.size(), taskTtlMinutes);
        }
        return stale.size();
    }

    private void finishFailure(AgentTask task, String status, String errorCode, String errorMessage) {
        task.setStatus(status);
        task.setEndedAt(LocalDateTime.now());
        task.setErrorCode(errorCode);
        task.setErrorMessage(errorMessage);
        task.setLeaseOwner(null);
        task.setLeaseExpireAt(null);
    }

    private void update(String taskId, Consumer<AgentTask> mutator) {
        agentTaskRepository.findById(taskId).ifPresent(task -> {
            mutator.accept(task);
            task.setUpdatedAt(LocalDateTime.now());
            agentTaskRepository.save(task);
            recordEvent(task);
        });
    }

    private void recordEvent(AgentTask task) {
        try {
            AgentTaskEvent event = new AgentTaskEvent();
            event.setId(UUID.randomUUID().toString().substring(0, 12));
            event.setTaskId(task.getId());
            event.setPhase(task.getPhase());
            event.setStatus(task.getStatus());
            event.setAttempt(task.getAttempts());
            event.setErrorCode(task.getErrorCode());
            event.setErrorMessage(task.getErrorMessage());
            event.setCreatedAt(LocalDateTime.now());
            agentTaskEventRepository.save(event);
        } catch (Exception e) {
            log.warn("Failed to record agent task event for {}: {}", task.getId(), e.getMessage());
        }
    }

    private boolean isActive(String status) {
        return STATUS_CREATED.equals(status)
                || STATUS_QUEUED.equals(status)
                || STATUS_RUNNING.equals(status);
    }

    private void metric(String name, String... tags) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(name, tags).increment();
    }
}
